package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.*;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.dto.*;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTimeRepository;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTimeService;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    @Autowired private ProductManufacturingTimeService manufacturingTimeService;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ProductionOrderRepository productionOrderRepository;
    @Autowired private ProductionOrderLineItemRepository lineItemRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** The "no date filter" range, as the controller spells it. */
    private static final java.time.OffsetDateTime DAWN =
            java.time.OffsetDateTime.parse("1900-01-01T00:00:00Z");
    private static final java.time.OffsetDateTime DUSK =
            java.time.OffsetDateTime.parse("9999-12-31T00:00:00Z");

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

    /**
     * An order with one line for the given product. Every boolean is set by hand:
     * the entity's Lombok builder does not apply field initialisers, and the
     * columns behind them are NOT NULL.
     */
    private ProductionOrderLineItem aLineItem(Product product) {
        int n = COUNTER.incrementAndGet();

        ProductionOrder order = productionOrderRepository.save(ProductionOrder.builder()
                .code("PN-" + n + "-" + System.nanoTime())
                .name("Nalog " + n)
                .status(ProductionOrderStatus.CREATED)
                .testingRequired(false)
                .isHighPriority(false)
                .isAnnounced(false)
                .hasSuccessiveDeliveries(false)
                .isActive(true)
                .build());

        ProductionOrderLineItem lineItem = new ProductionOrderLineItem();
        lineItem.setProductionOrder(order);
        lineItem.setProduct(product);
        lineItem.setQuantity(10);
        lineItem.setProductDescription("Stavka " + n);
        lineItem.setLineOrder(1);
        lineItem.setIsActive(true);
        return lineItemRepository.save(lineItem);
    }

    private Long createRequest(Long requesterId, Product product, ProductionOrderLineItem lineItem) {
        ManufacturingTimeRequestCreateRequest request = new ManufacturingTimeRequestCreateRequest();
        request.setProductId(product.getId());
        request.setRequestType(ManufacturingTimeRequestType.CREATE);
        request.setDescription("Potrebna nova norma");
        request.setProductionOrderLineItemId(lineItem == null ? null : lineItem.getId());
        return requestService.create(request, requesterId).id();
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
    @DisplayName("a PENDING request cannot be DECLINED directly — refusing still needs ownership")
    void pendingCannotBeDeclinedDirectly() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());

        // Completing claims on the way (see completingClaimsAnUnownedRequest);
        // refusing deliberately does not. Turning somebody's request down is not
        // something to fall into from the queue.
        assertThatThrownBy(() ->
                requestService.decline(requestId, users.get(1).getId(), "ne"))
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
        // 12.5 pieces an hour is 288 seconds each — the row shows both numbers,
        // so the fixture has to carry both.
        result.setManufacturingTimeSeconds(288);
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

    @Test
    @DisplayName("a request raised on an order line carries the line and its order")
    void requestCarriesTheLineItWasRaisedOn() {
        List<User> users = twoUsers();
        Product product = aProduct();
        ProductionOrderLineItem lineItem = aLineItem(product);

        var response = requestService.getById(
                createRequest(users.get(0).getId(), product, lineItem));

        assertThat(response.productionOrderLineItemId()).isEqualTo(lineItem.getId());
        assertThat(response.productionOrderId())
                .isEqualTo(lineItem.getProductionOrder().getId());
        assertThat(response.productionOrderCode())
                .isEqualTo(lineItem.getProductionOrder().getCode());
        assertThat(response.productionOrderLineDescription())
                .isEqualTo(lineItem.getProductDescription());
        // The line is the occasion; the subject is still the product.
        assertThat(response.productId()).isEqualTo(product.getId());
    }

    @Test
    @DisplayName("a standalone request has no order line")
    void standaloneRequestHasNoLine() {
        List<User> users = twoUsers();
        var response = requestService.getById(createRequest(users.get(0).getId(), aProduct()));

        assertThat(response.productionOrderLineItemId()).isNull();
        assertThat(response.productionOrderId()).isNull();
    }

    @Test
    @DisplayName("a line belonging to a different product is refused, not silently preferred")
    void lineFromAnotherProductIsRejected() {
        List<User> users = twoUsers();
        ProductionOrderLineItem otherProductsLine = aLineItem(aProduct());

        ManufacturingTimeRequestCreateRequest request = new ManufacturingTimeRequestCreateRequest();
        request.setProductId(aProduct().getId());
        request.setRequestType(ManufacturingTimeRequestType.CREATE);
        request.setDescription("Potrebna nova norma");
        request.setProductionOrderLineItemId(otherProductsLine.getId());

        assertThatThrownBy(() -> requestService.create(request, users.get(0).getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a line the order no longer has cannot be the occasion for a new request")
    void inactiveLineIsRejected() {
        List<User> users = twoUsers();
        Product product = aProduct();
        ProductionOrderLineItem lineItem = aLineItem(product);

        // How an order edit retires a line: it is deactivated, never deleted.
        lineItem.setIsActive(false);
        lineItemRepository.save(lineItem);

        assertThatThrownBy(() -> createRequest(users.get(0).getId(), product, lineItem))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("one open request per order line — a second is refused")
    void secondOpenRequestOnTheSameLineIsRejected() {
        List<User> users = twoUsers();
        Product product = aProduct();
        ProductionOrderLineItem lineItem = aLineItem(product);

        createRequest(users.get(0).getId(), product, lineItem);

        assertThatThrownBy(() -> createRequest(users.get(1).getId(), product, lineItem))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a closed request frees the line for a new one")
    void cancelledRequestFreesTheLine() {
        List<User> users = twoUsers();
        Product product = aProduct();
        ProductionOrderLineItem lineItem = aLineItem(product);

        Long first = createRequest(users.get(0).getId(), product, lineItem);
        requestService.cancel(first, users.get(0).getId(), "Greska.");

        assertThat(createRequest(users.get(0).getId(), product, lineItem)).isNotNull();
    }

    @Test
    @DisplayName("the order's live requests are listed, and narrow to one requester on demand")
    void openRequestsForOrderNarrowToRequester() {
        List<User> users = twoUsers();
        Product product = aProduct();
        ProductionOrderLineItem lineItem = aLineItem(product);
        Long orderId = lineItem.getProductionOrder().getId();

        createRequest(users.get(0).getId(), product, lineItem);

        assertThat(requestService.forProductionOrder(orderId, null)).hasSize(1);
        assertThat(requestService.forProductionOrder(orderId, users.get(0).getId())).hasSize(1);
        // Somebody who raised nothing on this order sees nothing on it.
        assertThat(requestService.forProductionOrder(orderId, users.get(1).getId())).isEmpty();
    }

    /** Assigns the request to the processor and hands the id straight back. */
    private Long claimed(Long requestId, Long processorId) {
        requestService.assign(requestId, processorId, null);
        return requestId;
    }

    /** Completes a CREATE request by producing a brand-new manufacturing time. */
    private Long completeWithNewTime(Long requestId, Long processorId, Product product) {
        var decision = new ManufacturingTimeRequestDecisionRequest();
        decision.setDecisionNote("Izracunato.");
        var result = new ProductManufacturingTimeCreateRequest();
        result.setProductId(product.getId());
        result.setTitle("Norma " + COUNTER.incrementAndGet());
        result.setProductName(product.getProductName());
        result.setProductsPerHour(new BigDecimal("12.5"));
        // 12.5 pieces an hour is 288 seconds each — the row shows both numbers,
        // so the fixture has to carry both.
        result.setManufacturingTimeSeconds(288);
        decision.setManufacturingTime(result);

        return requestService.complete(requestId, processorId, decision).resultManufacturingTimeId();
    }

    /** Completes a CREATE request by attaching a manufacturing time that exists. */
    private ManufacturingTimeRequestResponse completeWithExistingTime(
            Long requestId, Long processorId, Long manufacturingTimeId
    ) {
        var decision = new ManufacturingTimeRequestDecisionRequest();
        decision.setExistingManufacturingTimeId(manufacturingTimeId);
        return requestService.complete(requestId, processorId, decision);
    }

    @Test
    @DisplayName("one manufacturing time can answer several requests")
    void oneRecordAnswersManyRequests() {
        List<User> users = twoUsers();
        User requester = users.get(0);
        User processor = users.get(1);
        Product product = aProduct();

        // The first request produces the record...
        Long first = createRequest(requester.getId(), product);
        requestService.assign(first, processor.getId(), null);
        Long recordId = completeWithNewTime(first, processor.getId(), product);
        assertThat(recordId).isNotNull();

        // ...and the second is settled by that same record.
        Long second = createRequest(requester.getId(), product);
        requestService.assign(second, processor.getId(), null);
        var completed = completeWithExistingTime(second, processor.getId(), recordId);

        assertThat(completed.status()).isEqualTo(ManufacturingTimeRequestStatus.COMPLETED);
        assertThat(completed.resultManufacturingTimeId()).isEqualTo(recordId);

        // The first request keeps its result: sharing a record takes nothing away.
        assertThat(requestService.getById(first).resultManufacturingTimeId()).isEqualTo(recordId);
    }

    @Test
    @DisplayName("attaching does not take authorship away from the request that wrote the record")
    void attachingLeavesAuthorshipAlone() {
        List<User> users = twoUsers();
        Product product = aProduct();

        Long first = createRequest(users.get(0).getId(), product);
        requestService.assign(first, users.get(1).getId(), null);
        Long recordId = completeWithNewTime(first, users.get(1).getId(), product);

        Long second = createRequest(users.get(0).getId(), product);
        requestService.assign(second, users.get(1).getId(), null);
        completeWithExistingTime(second, users.get(1).getId(), recordId);

        // source_request_id still names the request that produced the record.
        var record = manufacturingTimeRepository.findById(recordId).orElseThrow();
        assertThat(record.getSourceRequest().getId()).isEqualTo(first);
        assertThat(manufacturingTimeRepository.findBySourceRequest_Id(second)).isEmpty();
    }

    @Test
    @DisplayName("a record for another product cannot answer the request")
    void attachedRecordMustMatchTheProduct() {
        List<User> users = twoUsers();
        Product product = aProduct();
        Product otherProduct = aProduct();

        Long donor = createRequest(users.get(0).getId(), otherProduct);
        requestService.assign(donor, users.get(1).getId(), null);
        Long otherProductsRecord = completeWithNewTime(donor, users.get(1).getId(), otherProduct);

        Long request = createRequest(users.get(0).getId(), product);
        requestService.assign(request, users.get(1).getId(), null);

        assertThatThrownBy(() ->
                completeWithExistingTime(request, users.get(1).getId(), otherProductsRecord))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a request that names a target cannot be answered by some other record")
    void targetedRequestRefusesAnAttachment() {
        List<User> users = twoUsers();
        Product product = aProduct();

        Long create = createRequest(users.get(0).getId(), product);
        requestService.assign(create, users.get(1).getId(), null);
        Long recordId = completeWithNewTime(create, users.get(1).getId(), product);

        ManufacturingTimeRequestCreateRequest recalculate = new ManufacturingTimeRequestCreateRequest();
        recalculate.setProductId(product.getId());
        recalculate.setRequestType(ManufacturingTimeRequestType.RECALCULATE);
        recalculate.setDescription("Ponovi obracun");
        recalculate.setTargetManufacturingTimeId(recordId);
        Long targeted = requestService.create(recalculate, users.get(0).getId()).id();
        requestService.assign(targeted, users.get(1).getId(), null);

        Long strangerRecord = completeWithNewTime(
                claimed(createRequest(users.get(0).getId(), product), users.get(1).getId()),
                users.get(1).getId(), product);

        assertThatThrownBy(() ->
                completeWithExistingTime(targeted, users.get(1).getId(), strangerRecord))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a declined request keeps no result, and the database agrees")
    void declinedRequestHasNoResult() {
        List<User> users = twoUsers();
        Long requestId = createRequest(users.get(0).getId(), aProduct());
        requestService.assign(requestId, users.get(1).getId(), null);

        var declined = requestService.decline(requestId, users.get(1).getId(), "Nije potrebno.");

        assertThat(declined.status()).isEqualTo(ManufacturingTimeRequestStatus.DECLINED);
        assertThat(declined.resultManufacturingTimeId()).isNull();
    }

    @Test
    @DisplayName("the result carries its title and date, so a list needs no second call")
    void resultIsDescribedOnTheResponse() {
        List<User> users = twoUsers();
        Product product = aProduct();
        Long requestId = createRequest(users.get(0).getId(), product);
        requestService.assign(requestId, users.get(1).getId(), null);
        completeWithNewTime(requestId, users.get(1).getId(), product);

        var response = requestService.getById(requestId);
        // The row is read for the numbers, so those are what travel with it.
        assertThat(response.resultProductsPerHour()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(response.resultManufacturingTimeSeconds()).isEqualTo(288);
    }

    @Test
    @DisplayName("completing an unowned request claims it on the way")
    void completingClaimsAnUnownedRequest() {
        List<User> users = twoUsers();
        Product product = aProduct();

        // Never assigned: the processor goes straight from the queue to a result.
        Long requestId = createRequest(users.get(0).getId(), product);
        completeWithNewTime(requestId, users.get(1).getId(), product);

        var completed = requestService.getById(requestId);
        assertThat(completed.status()).isEqualTo(ManufacturingTimeRequestStatus.COMPLETED);
        // assigned_to still names whoever did the work — that is the point of the rule.
        assertThat(completed.assignedToUserId()).isEqualTo(users.get(1).getId());
        assertThat(completed.processedByUserId()).isEqualTo(users.get(1).getId());
    }

    @Test
    @DisplayName("a request somebody else owns is still refused, never taken over")
    void completingDoesNotTakeOverSomebodyElsesRequest() {
        List<User> users = twoUsers();
        User thirdParty = newUser("other-processor");
        Product product = aProduct();

        Long requestId = createRequest(users.get(0).getId(), product);
        requestService.assign(requestId, users.get(1).getId(), null);

        assertThatThrownBy(() -> completeWithNewTime(requestId, thirdParty.getId(), product))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(requestService.getById(requestId).assignedToUserId())
                .isEqualTo(users.get(1).getId());
    }

    @Test
    @DisplayName("reworking an existing record and settling the request happen together")
    void attachingWithAnUpdateRewritesTheRecordAndClaimsAuthorship() {
        List<User> users = twoUsers();
        Product product = aProduct();

        Long first = createRequest(users.get(0).getId(), product);
        Long recordId = completeWithNewTime(first, users.get(1).getId(), product);

        Long second = createRequest(users.get(0).getId(), product);

        var decision = new ManufacturingTimeRequestDecisionRequest();
        decision.setExistingManufacturingTimeId(recordId);
        var update = new ProductManufacturingTimeUpdateRequest();
        update.setTitle("Prepravljena norma");
        update.setProductsPerHour(new BigDecimal("20.0"));
        decision.setManufacturingTimeUpdate(update);

        var completed = requestService.complete(second, users.get(1).getId(), decision);

        assertThat(completed.resultManufacturingTimeId()).isEqualTo(recordId);
        assertThat(completed.resultProductsPerHour()).isEqualByComparingTo(new BigDecimal("20.0"));

        var record = manufacturingTimeRepository.findById(recordId).orElseThrow();
        assertThat(record.getTitle()).isEqualTo("Prepravljena norma");
        // Reworking IS authorship, so the stamp moves to the request that did it.
        assertThat(record.getSourceRequest().getId()).isEqualTo(second);
    }

    @Test
    @DisplayName("plain attaching leaves the record's numbers alone")
    void attachingWithoutAnUpdateChangesNothing() {
        List<User> users = twoUsers();
        Product product = aProduct();

        Long first = createRequest(users.get(0).getId(), product);
        Long recordId = completeWithNewTime(first, users.get(1).getId(), product);
        var before = manufacturingTimeRepository.findById(recordId).orElseThrow().getTitle();

        Long second = createRequest(users.get(0).getId(), product);
        completeWithExistingTime(second, users.get(1).getId(), recordId);

        assertThat(manufacturingTimeRepository.findById(recordId).orElseThrow().getTitle())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("\"mine\" means raised by me OR taken by me, not both")
    void mineCoversBothWhatIRaisedAndWhatITook() {
        List<User> users = twoUsers();
        User requester = users.get(0);
        User processor = users.get(1);

        Long raisedByRequester = createRequest(requester.getId(), aProduct());
        Long takenByProcessor = createRequest(requester.getId(), aProduct());
        requestService.assign(takenByProcessor, processor.getId(), null);
        // Somebody else's, untouched by either of them.
        Long strangersRequest = createRequest(newUser("stranger").getId(), aProduct());

        var pageable = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));

        var processorsOwn = requestService
                .search(null, null, null, null, null, processor.getId(), DAWN, DUSK, pageable)
                .map(ManufacturingTimeRequestResponse::id)
                .toList();

        // Taken by them, so it counts as theirs even though they did not raise it.
        assertThat(processorsOwn).contains(takenByProcessor);
        assertThat(processorsOwn).doesNotContain(raisedByRequester, strangersRequest);

        var requestersOwn = requestService
                .search(null, null, null, null, null, requester.getId(), DAWN, DUSK, pageable)
                .map(ManufacturingTimeRequestResponse::id)
                .toList();

        // Raised by them — including the one somebody else is now processing.
        assertThat(requestersOwn).contains(raisedByRequester, takenByProcessor);
        assertThat(requestersOwn).doesNotContain(strangersRequest);
    }

    @Test
    @DisplayName("a RECALCULATE request is completed by rewriting the record it targets")
    void recalculateRewritesItsTarget() {
        List<User> users = twoUsers();
        User requester = users.get(0);
        User processor = users.get(1);
        Product product = aProduct();

        Long create = createRequest(requester.getId(), product);
        Long recordId = completeWithNewTime(create, processor.getId(), product);

        ManufacturingTimeRequestCreateRequest recalculate = new ManufacturingTimeRequestCreateRequest();
        recalculate.setProductId(product.getId());
        recalculate.setRequestType(ManufacturingTimeRequestType.RECALCULATE);
        recalculate.setDescription("Ponovi obracun po novoj normi");
        recalculate.setTargetManufacturingTimeId(recordId);
        Long targeted = requestService.create(recalculate, requester.getId()).id();

        // Exactly what the screen sends: the new numbers, and no record id — the
        // request already says which record it is about.
        var decision = new ManufacturingTimeRequestDecisionRequest();
        var update = new ProductManufacturingTimeUpdateRequest();
        update.setProductsPerHour(new BigDecimal("33.0"));
        decision.setManufacturingTimeUpdate(update);

        var completed = requestService.complete(targeted, processor.getId(), decision);

        assertThat(completed.status()).isEqualTo(ManufacturingTimeRequestStatus.COMPLETED);
        assertThat(completed.resultManufacturingTimeId()).isEqualTo(recordId);

        var record = manufacturingTimeRepository.findById(recordId).orElseThrow();
        assertThat(record.getProductsPerHour()).isEqualByComparingTo(new BigDecimal("33.0"));
        // Reworking is authorship, so the stamp moves to the request that did it.
        assertThat(record.getSourceRequest().getId()).isEqualTo(targeted);
    }

    @Test
    @DisplayName("the picker offers free work and your own, never somebody else's in progress")
    void pickableIsFreeWorkPlusYourOwn() {
        List<User> users = twoUsers();
        User processor = users.get(1);
        User colleague = newUser("colleague");

        Long free = createRequest(users.get(0).getId(), aProduct());

        Long mine = createRequest(users.get(0).getId(), aProduct());
        requestService.assign(mine, processor.getId(), null);

        Long theirs = createRequest(users.get(0).getId(), aProduct());
        requestService.assign(theirs, colleague.getId(), null);

        var offered = requestService.pickableRequests(processor.getId(), null)
                .stream()
                .map(ManufacturingTimeRequestResponse::id)
                .toList();

        assertThat(offered).contains(free, mine);
        // Somebody else is already on it; offering it only invites duplicate work.
        assertThat(offered).doesNotContain(theirs);
    }

    @Test
    @DisplayName("records that answer a request are shared, whoever produced them")
    void recordsAnsweringRequestsAreShared() {
        List<User> users = twoUsers();
        Product product = aProduct();

        Long request = createRequest(users.get(0).getId(), product);
        Long fromRequest = completeWithNewTime(request, users.get(1).getId(), product);

        // Made by hand by somebody else, answering nothing.
        var standalone = new ProductManufacturingTimeCreateRequest();
        standalone.setProductId(product.getId());
        standalone.setTitle("Licna beleska");
        standalone.setProductName(product.getProductName());
        standalone.setProductsPerHour(new BigDecimal("9.0"));
        Long personal = manufacturingTimeService
                .createForUser(standalone, users.get(0))
                .getId();

        var shared = manufacturingTimeService.getAnsweringRequests()
                .stream()
                .map(ProductManufacturingTimeDto::getId)
                .toList();

        assertThat(shared).contains(fromRequest);
        // A private working record stays out of the shared list.
        assertThat(shared).doesNotContain(personal);
    }

    @Test
    @DisplayName("a page reads as groups: waiting first, then in progress, then finished")
    void listIsRankedByStatusThenNewestFirst() {
        List<User> users = twoUsers();
        User requester = users.get(0);
        User processor = users.get(1);
        Product product = aProduct();

        Long waiting = createRequest(requester.getId(), product);
        Long inProgress = createRequest(requester.getId(), product);
        requestService.assign(inProgress, processor.getId(), null);
        Long finished = createRequest(requester.getId(), product);
        completeWithNewTime(finished, processor.getId(), product);

        var ids = requestService
                .search(null, product.getId(), null, null, null, null, DAWN, DUSK,
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(ManufacturingTimeRequestResponse::id)
                .toList();

        // Created oldest-first above, so plain newest-first would invert this.
        assertThat(ids).containsExactly(waiting, inProgress, finished);
    }

    @Test
    @DisplayName("the date range is a calendar day, inclusive at both ends")
    void searchNarrowsToACalendarRange() {
        List<User> users = twoUsers();
        Product product = aProduct();
        Long today = createRequest(users.get(0).getId(), product);

        var startOfToday = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        var startOfTomorrow = startOfToday.plusDays(1);

        var within = requestService
                .search(null, product.getId(), null, null, null, null,
                        startOfToday, startOfTomorrow,
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(ManufacturingTimeRequestResponse::id)
                .toList();
        assertThat(within).contains(today);

        // Yesterday only: the upper bound is exclusive, so today falls outside.
        var before = requestService
                .search(null, product.getId(), null, null, null, null,
                        startOfToday.minusDays(1), startOfToday,
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(ManufacturingTimeRequestResponse::id)
                .toList();
        assertThat(before).doesNotContain(today);
    }

    @Test
    @DisplayName("the date direction flips inside a status group, not across groups")
    void searchOrdersByChosenDirection() {
        List<User> users = twoUsers();
        Product product = aProduct();

        Long first = createRequest(users.get(0).getId(), product);
        Long second = createRequest(users.get(0).getId(), product);

        var newest = requestService
                .search(null, product.getId(), null, null, null, null, DAWN, DUSK,
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(ManufacturingTimeRequestResponse::id)
                .toList();
        assertThat(newest).containsExactly(second, first);

        var oldest = requestService
                .search(null, product.getId(), null, null, null, null, DAWN, DUSK,
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "createdAt")))
                .map(ManufacturingTimeRequestResponse::id)
                .toList();
        assertThat(oldest).containsExactly(first, second);
    }

    @Test
    @DisplayName("an order line keeps showing its answer after the request is finished")
    void finishedRequestStillShowsOnItsLine() {
        List<User> users = twoUsers();
        Product product = aProduct();
        ProductionOrderLineItem lineItem = aLineItem(product);
        Long orderId = lineItem.getProductionOrder().getId();

        Long requestId = createRequest(users.get(0).getId(), product, lineItem);
        completeWithNewTime(requestId, users.get(1).getId(), product);

        var onOrder = requestService.forProductionOrder(orderId, null);

        // Still listed, now carrying the numbers the line is read for.
        assertThat(onOrder).hasSize(1);
        assertThat(onOrder.getFirst().status()).isEqualTo(ManufacturingTimeRequestStatus.COMPLETED);
        assertThat(onOrder.getFirst().resultManufacturingTimeSeconds()).isEqualTo(288);
    }

    @Test
    @DisplayName("a refused request leaves the line as it was, free to ask again")
    void refusedRequestLeavesNoTraceOnTheLine() {
        List<User> users = twoUsers();
        Product product = aProduct();
        ProductionOrderLineItem lineItem = aLineItem(product);
        Long orderId = lineItem.getProductionOrder().getId();

        Long requestId = createRequest(users.get(0).getId(), product, lineItem);
        requestService.assign(requestId, users.get(1).getId(), null);
        requestService.decline(requestId, users.get(1).getId(), "Nije potrebno.");

        assertThat(requestService.forProductionOrder(orderId, null)).isEmpty();
    }
}
