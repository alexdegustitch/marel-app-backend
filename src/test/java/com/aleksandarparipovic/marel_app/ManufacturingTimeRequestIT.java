package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.*;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.dto.*;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTimeRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deliberately NOT {@code @Transactional}.
 *
 * <p>These tests assert real transaction boundaries — above all that a failed
 * completion rolls back the status change with it. Wrapping the test in one
 * shared transaction would defeat that: the service would join the test's
 * transaction, and the mutated entity would still sit in the persistence context
 * reading COMPLETED even though the database rolled back. Each service call
 * therefore gets its own transaction, and every fixture is uniquely named so the
 * tests stay independent without cleanup.
 */
class ManufacturingTimeRequestIT extends AbstractIntegrationTest {

    @Autowired private ManufacturingTimeRequestService requestService;
    @Autowired private ManufacturingTimeRequestRepository requestRepository;
    @Autowired private ProductManufacturingTimeRepository manufacturingTimeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /**
     * A requester and a separate processor. Created rather than looked up: the test
     * schema starts with only the bootstrap admin, and these tests are specifically
     * about two DIFFERENT people, so the fixture must guarantee that.
     */
    private List<User> twoUsers() {
        return List.of(newUser("requester"), newUser("processor"));
    }

    private User newUser(String prefix) {
        int n = COUNTER.incrementAndGet();
        Role role = roleRepository.findAll().stream().findFirst().orElseThrow();

        return userRepository.save(User.builder()
                .username(prefix + "-" + n + "-" + System.nanoTime())
                .passwordHash("x")
                .firstName("Test")
                .lastName(prefix + n)
                .emailAddress(prefix + n + "-" + System.nanoTime() + "@example.rs")
                .role(role)
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build());
    }

    private Product aProduct() {
        Product product = new Product();
        product.setProductName("Test proizvod " + COUNTER.incrementAndGet());
        product.setActive(true);
        return productRepository.save(product);
    }

    private Long createRequest(Long requesterId, Product product) {
        ManufacturingTimeRequestCreateRequest request = new ManufacturingTimeRequestCreateRequest();
        request.setProductId(product.getId());
        request.setRequestType(ManufacturingTimeRequestType.CREATE);
        request.setDescription("Potrebna nova norma");
        return requestService.create(request, requesterId).id();
    }

    @Test
    @DisplayName("a new request starts PENDING and unassigned")
    void newRequestStartsPending() {
        List<User> users = twoUsers();
        var response = requestService.getById(createRequest(users.get(0).getId(), aProduct()));

        assertThat(response.status()).isEqualTo(ManufacturingTimeRequestStatus.PENDING);
        assertThat(response.assignedToUserId()).isNull();
        assertThat(response.processedByUserId()).isNull();
    }

    @Test
    @DisplayName("UPDATE without a target record is rejected")
    void updateRequiresTarget() {
        List<User> users = twoUsers();
        Product product = aProduct();

        ManufacturingTimeRequestCreateRequest request = new ManufacturingTimeRequestCreateRequest();
        request.setProductId(product.getId());
        request.setRequestType(ManufacturingTimeRequestType.UPDATE);
        request.setDescription("Izmena");

        assertThatThrownBy(() -> requestService.create(request, users.get(0).getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a PENDING request cannot be completed — it must be claimed first")
    void pendingCannotBeCompletedDirectly() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());

        // Not assigned to anyone yet, so even a legitimate processor is refused.
        assertThatThrownBy(() ->
                requestService.complete(requestId, users.get(1).getId(), null))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(requestService.getById(requestId).status())
                .isEqualTo(ManufacturingTimeRequestStatus.PENDING);
    }

    @Test
    @DisplayName("claiming moves the request to IN_REVIEW and records the owner")
    void claimingAssignsOwner() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());

        var assigned = requestService.assign(requestId, users.get(1).getId(), null);

        assertThat(assigned.status()).isEqualTo(ManufacturingTimeRequestStatus.IN_REVIEW);
        assertThat(assigned.assignedToUserId()).isEqualTo(users.get(1).getId());
    }

    @Test
    @DisplayName("a requester cannot process their own request")
    void requesterCannotProcessOwnRequest() {
        List<User> users = twoUsers();
        User requester = users.get(0);
        Long requestId = createRequest(requester.getId(), aProduct());

        requestService.assign(requestId, requester.getId(), null);

        assertThatThrownBy(() -> requestService.complete(requestId, requester.getId(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> requestService.decline(requestId, requester.getId(), "ne"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("completing produces the manufacturing time and links it to the request")
    void completionProducesLinkedResult() {
        List<User> users = twoUsers();
        Product product = aProduct();
        Long requestId = createRequest(users.get(0).getId(), product);
        requestService.assign(requestId, users.get(1).getId(), null);

        var decision = new ManufacturingTimeRequestDecisionRequest();
        decision.setDecisionNote("Izracunato.");
        var result = new ProductManufacturingTimeCreateRequest();
        result.setProductId(product.getId());
        result.setTitle("Norma");
        result.setProductName(product.getProductName());
        result.setProductsPerHour(new BigDecimal("12.5"));
        decision.setManufacturingTime(result);

        var completed = requestService.complete(requestId, users.get(1).getId(), decision);

        assertThat(completed.status()).isEqualTo(ManufacturingTimeRequestStatus.COMPLETED);
        assertThat(completed.processedByUserId()).isEqualTo(users.get(1).getId());
        assertThat(completed.processedAt()).isNotNull();
        assertThat(completed.resultManufacturingTimeId()).isNotNull();

        assertThat(manufacturingTimeRepository.findBySourceRequest_Id(requestId)).isPresent();
    }

    @Test
    @DisplayName("if the result cannot be produced, the completion rolls back with it")
    void failedResultLeavesRequestOpen() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());
        requestService.assign(requestId, users.get(1).getId(), null);

        // A CREATE completion with no result payload must fail...
        assertThatThrownBy(() ->
                requestService.complete(requestId, users.get(1).getId(), null))
                .isInstanceOf(IllegalArgumentException.class);

        // ...and must NOT leave a COMPLETED request with nothing to show for it.
        var unchanged = requestRepository.findDetailById(requestId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ManufacturingTimeRequestStatus.IN_REVIEW);
        assertThat(unchanged.getProcessedBy()).isNull();
    }

    @Test
    @DisplayName("declining records the processor and produces no result")
    void declineProducesNoResult() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());
        requestService.assign(requestId, users.get(1).getId(), null);

        var declined = requestService.decline(requestId, users.get(1).getId(), "Nije potrebno.");

        assertThat(declined.status()).isEqualTo(ManufacturingTimeRequestStatus.DECLINED);
        assertThat(declined.processedByUserId()).isEqualTo(users.get(1).getId());
        assertThat(declined.resultManufacturingTimeId()).isNull();
        assertThat(manufacturingTimeRepository.findBySourceRequest_Id(requestId)).isEmpty();
    }

    @Test
    @DisplayName("a finished request is terminal and cannot be processed again")
    void terminalStatusIsTerminal() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());
        requestService.assign(requestId, users.get(1).getId(), null);
        requestService.decline(requestId, users.get(1).getId(), "ne");

        assertThatThrownBy(() -> requestService.decline(requestId, users.get(1).getId(), "opet"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> requestService.cancel(requestId, users.get(0).getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the requester may cancel while the request is still open")
    void requesterMayCancelOpenRequest() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());

        var cancelled = requestService.cancel(requestId, users.get(0).getId(), "Nije vise potrebno.");

        assertThat(cancelled.status()).isEqualTo(ManufacturingTimeRequestStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(cancelled.processedByUserId()).isNull();
    }
}
