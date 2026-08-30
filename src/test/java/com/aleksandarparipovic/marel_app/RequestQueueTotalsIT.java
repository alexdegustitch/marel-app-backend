package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.manufacturing_time_request.*;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.dto.*;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the queue reports about ITSELF — the number beside a status group, which
 * is the only thing telling a reader whether "prikazi jos" is worth pressing.
 *
 * <p>These requests carry no order line and, until somebody takes them, no
 * assignee. That is the ordinary case, and it is exactly the case a count query
 * built from INNER joins drops. The page then holds more rows than the count
 * admits to, PageImpl substitutes "however many are on this page", and the total
 * silently becomes the page size — so a decision never moves the number and the
 * button never appears. Hence the sizes below: the point is the total at a page
 * size SMALLER than the queue.
 */
class RequestQueueTotalsIT extends AbstractIntegrationTest {

    @Autowired private ManufacturingTimeRequestService requestService;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final java.time.OffsetDateTime DAWN =
            java.time.OffsetDateTime.parse("1900-01-01T00:00:00Z");
    private static final java.time.OffsetDateTime DUSK =
            java.time.OffsetDateTime.parse("9999-12-31T00:00:00Z");

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

    private Long createRequest(Long requesterId) {
        ManufacturingTimeRequestCreateRequest request = new ManufacturingTimeRequestCreateRequest();
        request.setProductId(aProduct().getId());
        request.setRequestType(ManufacturingTimeRequestType.CREATE);
        request.setDescription("Potrebna nova norma");
        return requestService.create(request, requesterId).id();
    }

    /** The queue as the screen asks for it: one status, first page, given size. */
    private org.springframework.data.domain.Page<ManufacturingTimeRequestResponse> queue(
            ManufacturingTimeRequestStatus status, Long requesterId, int size
    ) {
        return requestService.search(
                status, null, requesterId, null, null, null, DAWN, DUSK,
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Test
    @DisplayName("a full page still reports the whole queue, not its own size")
    void fullPageReportsTheWholeQueue() {
        User requester = newUser("requester");
        User processor = newUser("processor");

        for (int i = 0; i < 12; i++) {
            Long id = createRequest(requester.getId());
            requestService.assign(id, processor.getId(), null);
            requestService.decline(id, processor.getId(), "ne");
        }

        var firstPage = queue(ManufacturingTimeRequestStatus.DECLINED, requester.getId(), 5);
        assertThat(firstPage.getContent()).hasSize(5);
        assertThat(firstPage.getTotalElements())
                .as("five on screen, twelve in the queue")
                .isEqualTo(12);

        assertThat(queue(ManufacturingTimeRequestStatus.DECLINED, requester.getId(), 15)
                .getContent())
                .as("asking for more must actually bring more")
                .hasSize(12);
    }

    @Test
    @DisplayName("declining one more raises the total, even on a full page")
    void decliningRaisesTheTotal() {
        User requester = newUser("requester");
        User processor = newUser("processor");

        for (int i = 0; i < 5; i++) {
            Long id = createRequest(requester.getId());
            requestService.assign(id, processor.getId(), null);
            requestService.decline(id, processor.getId(), "ne");
        }

        assertThat(queue(ManufacturingTimeRequestStatus.DECLINED, requester.getId(), 5)
                .getTotalElements()).isEqualTo(5);

        Long sixth = createRequest(requester.getId());
        requestService.assign(sixth, processor.getId(), null);
        requestService.decline(sixth, processor.getId(), "ne");

        assertThat(queue(ManufacturingTimeRequestStatus.DECLINED, requester.getId(), 5)
                .getTotalElements())
                .as("the sixth decline counts even though the page still holds five")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("an unclaimed request is counted, though it has no assignee")
    void unclaimedRequestsAreCounted() {
        User requester = newUser("requester");
        for (int i = 0; i < 7; i++) createRequest(requester.getId());

        assertThat(queue(ManufacturingTimeRequestStatus.PENDING, requester.getId(), 1)
                .getTotalElements())
                .as("the type picker asks for one row and reads only the total")
                .isEqualTo(7);
    }
}
