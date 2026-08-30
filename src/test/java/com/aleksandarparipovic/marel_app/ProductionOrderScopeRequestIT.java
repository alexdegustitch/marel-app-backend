package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.production_order_scope_request.*;
import com.aleksandarparipovic.marel_app.production_order_scope_request.dto.*;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order-scope workflow: what an order is made of, and who may say so.
 *
 * <p>Deliberately NOT {@code @Transactional}, for the same reason as
 * {@code ManufacturingTimeRequestIT}: these tests assert real transaction
 * boundaries — above all that a submitted answer is genuinely closed to further
 * writes — and one shared transaction would let the persistence context answer
 * from memory instead of from the database. Every fixture is uniquely named so
 * the tests stay independent without cleanup.
 */
class ProductionOrderScopeRequestIT extends AbstractIntegrationTest {

    @Autowired private ProductionOrderScopeRequestService service;
    @Autowired private ProductionOrderScopeRequestRepository requestRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private OperationRepository operationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ProductionOrderRepository orderRepository;
    @Autowired private ProductionOrderLineItemRepository lineItemRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Speaks as somebody holding the process capability.
     *
     * <p>Needed because {@code editable} and the read-all narrowing are answered
     * from the security context, and a test with none is nobody — which is the
     * correct answer for those, and the wrong fixture for asserting them.
     */
    private void asSupervisor() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "supervisor", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_supervisor"))));
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

    /** A product whose operations the catalogue never gave a quantity. */
    private Product aProductWithoutQuantities(String... operationNames) {
        Product product = aProduct(operationNames);
        operationRepository.findByProductIdAndArchivedAtIsNull(product.getId())
                .forEach(operation -> {
                    operation.setUnitsPerProduct(null);
                    operationRepository.save(operation);
                });
        return product;
    }

    private Product aProduct(String... operationNames) {
        Product product = new Product();
        product.setProductName("Test proizvod " + COUNTER.incrementAndGet());
        product.setActive(true);
        Product saved = productRepository.save(product);

        for (String name : operationNames) {
            Operation operation = new Operation();
            operation.setProduct(saved);
            operation.setOpName(name);
            operation.setUnitsPerProduct(2);
            operation.setNormRequired(false);
            operationRepository.save(operation);
        }
        return saved;
    }

    /**
     * An order with one line per product. Every boolean is set by hand: the
     * entity's Lombok builder does not apply field initialisers, and the columns
     * behind them are NOT NULL.
     */
    private ProductionOrder anOrder(List<Product> products) {
        int n = COUNTER.incrementAndGet();

        ProductionOrder order = orderRepository.save(ProductionOrder.builder()
                .code("PN-" + n + "-" + System.nanoTime())
                .name("Nalog " + n)
                .status(ProductionOrderStatus.CREATED)
                .testingRequired(false)
                .isHighPriority(false)
                .isAnnounced(false)
                .hasSuccessiveDeliveries(false)
                .isActive(true)
                .build());

        int line = 1;
        for (Product product : products) {
            ProductionOrderLineItem item = new ProductionOrderLineItem();
            item.setProductionOrder(order);
            item.setProduct(product);
            item.setQuantity(10);
            item.setProductDescription("Stavka " + line);
            item.setNote("Napomena sa stavke " + line);
            item.setLineOrder(line++);
            item.setIsActive(true);
            lineItemRepository.save(item);
        }
        return order;
    }

    private ProductionOrderScopeRequestResponse createForOrder(Long requesterId, ProductionOrder order) {
        ProductionOrderScopeRequestCreateRequest request = new ProductionOrderScopeRequestCreateRequest();
        request.setProductionOrderId(order.getId());
        request.setScope(ProductionOrderScopeRequestScope.ORDER);
        return service.create(request, requesterId);
    }

    /** The answer that keeps every operation, at the catalogue's own quantity. */
    private ProductionOrderScopeResultRequest answerFor(
            ProductionOrderScopeRequestDetailResponse detail
    ) {
        ProductionOrderScopeResultRequest payload = new ProductionOrderScopeResultRequest();
        payload.setItems(detail.items().stream().map(item -> {
            ProductionOrderScopeResultRequest.Item answer = new ProductionOrderScopeResultRequest.Item();
            answer.setItemId(item.line().itemId());
            answer.setOperations(item.operations().stream().map(operation -> {
                ProductionOrderScopeResultRequest.Operation decided =
                        new ProductionOrderScopeResultRequest.Operation();
                decided.setOperationId(operation.operationId());
                decided.setNeeded(true);
                decided.setUnitsPerProduct(operation.unitsPerProduct());
                return decided;
            }).toList());
            return answer;
        }).toList());
        return payload;
    }

    @Test
    @DisplayName("a request for the whole order covers every line, with each line's own note")
    void orderRequestCoversEveryLine() {
        User requester = newUser("requester");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje"), aProduct("Varenje")));

        var response = createForOrder(requester.getId(), order);

        assertThat(response.status()).isEqualTo(ProductionOrderScopeRequestStatus.PENDING);
        assertThat(response.resultState()).isNull();
        assertThat(response.assignedToUserId()).isNull();
        assertThat(response.lines()).hasSize(2);
        // The order's own note is the starting point, copied onto the request so
        // it keeps saying what was asked even if the order is edited later.
        assertThat(response.lines()).extracting(ProductionOrderScopeLineResponse::note)
                .containsExactly("Napomena sa stavke 1", "Napomena sa stavke 2");
    }

    @Test
    @DisplayName("a request for one line must name exactly one")
    void lineRequestNamesOneLine() {
        User requester = newUser("requester");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje"), aProduct("Varenje")));
        List<ProductionOrderLineItem> lines =
                lineItemRepository.findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(order.getId());

        ProductionOrderScopeRequestCreateRequest twoLines = new ProductionOrderScopeRequestCreateRequest();
        twoLines.setProductionOrderId(order.getId());
        twoLines.setScope(ProductionOrderScopeRequestScope.LINE_ITEM);
        twoLines.setItems(lines.stream().map(line -> {
            ProductionOrderScopeRequestCreateRequest.Item item =
                    new ProductionOrderScopeRequestCreateRequest.Item();
            item.setProductionOrderLineItemId(line.getId());
            return item;
        }).toList());

        assertThatThrownBy(() -> service.create(twoLines, requester.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tačno jednu stavku");
    }

    @Test
    @DisplayName("a line already covered by a live request cannot be asked about again")
    void oneLiveRequestPerLine() {
        User requester = newUser("requester");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje")));
        createForOrder(requester.getId(), order);

        assertThatThrownBy(() -> createForOrder(requester.getId(), order))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("već postoji zahtev");

        // ...and the line stops being offered, rather than being offered and refused.
        assertThat(service.proposedLines(order.getId())).isEmpty();
    }

    @Test
    @DisplayName("the requester never answers their own request")
    void requesterDoesNotAnswerTheirOwn() {
        User requester = newUser("requester");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        ProductionOrderScopeRequestDetailResponse detail =
                service.getDetail(requestId, requester.getId());

        assertThatThrownBy(() ->
                service.saveDraft(requestId, requester.getId(), answerFor(detail)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("before anything is saved, the modal proposes the product's own operations")
    void modalStartsFromTheCatalogue() {
        User requester = newUser("requester");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje", "Bušenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        var detail = service.getDetail(requestId, requester.getId());

        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).operations())
                .extracting(ProductionOrderScopeOperationResponse::operationName)
                .containsExactlyInAnyOrder("Sečenje", "Bušenje");
        // Everything is proposed as needed, at the catalogue's quantity — the
        // starting point the processor edits, not a decision anybody has made.
        assertThat(detail.items().get(0).operations())
                .allMatch(ProductionOrderScopeOperationResponse::needed)
                .allMatch(operation -> operation.unitsPerProduct() == 2);
    }

    @Test
    @DisplayName("an operation the catalogue gave no quantity is proposed as one per assembly")
    void missingQuantitiesAreProposedAsOne() {
        User requester = newUser("requester");
        ProductionOrder order = anOrder(List.of(aProductWithoutQuantities("Sečenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        var operation = service.getDetail(requestId, requester.getId())
                .items().get(0).operations().get(0);

        assertThat(operation.unitsPerProduct()).isEqualTo(1);
        // The snapshot still says what the catalogue actually held, so nothing
        // reads as an override that was only ever a proposal.
        assertThat(operation.unitsPerProductSnapshot()).isNull();
    }

    @Test
    @DisplayName("saving keeps the request in review, as a draft the processor still owns")
    void savingLeavesADraft() {
        User requester = newUser("requester");
        User processor = newUser("processor");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje", "Bušenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        service.assign(requestId, processor.getId(), null);
        asSupervisor();

        var saved = service.saveDraft(
                requestId, processor.getId(),
                answerFor(service.getDetail(requestId, processor.getId())));

        assertThat(saved.request().status()).isEqualTo(ProductionOrderScopeRequestStatus.IN_REVIEW);
        assertThat(saved.request().resultState()).isEqualTo(ProductionOrderScopeResultState.DRAFT);
        assertThat(saved.request().processedAt()).isNull();

        // Still theirs to change, which is the whole point of saving separately.
        var reread = service.getDetail(requestId, processor.getId());
        assertThat(reread.editable()).isTrue();
        assertThat(reread.items().get(0).operations()).hasSize(2);
    }

    @Test
    @DisplayName("a saved answer keeps what was switched off, and the quantity that was changed")
    void theAnswerKeepsWhatWasDecided() {
        User requester = newUser("requester");
        User processor = newUser("processor");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje", "Bušenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        service.assign(requestId, processor.getId(), null);
        asSupervisor();

        var detail = service.getDetail(requestId, processor.getId());
        ProductionOrderScopeResultRequest payload = answerFor(detail);
        // First operation off, second one at three per assembly instead of two.
        payload.getItems().get(0).getOperations().get(0).setNeeded(false);
        payload.getItems().get(0).getOperations().get(0).setUnitsPerProduct(null);
        payload.getItems().get(0).getOperations().get(1).setUnitsPerProduct(3);

        var saved = service.saveDraft(requestId, processor.getId(), payload);

        var offOperation = saved.items().get(0).operations().stream()
                .filter(operation -> !operation.needed()).findFirst().orElseThrow();
        var onOperation = saved.items().get(0).operations().stream()
                .filter(ProductionOrderScopeOperationResponse::needed).findFirst().orElseThrow();

        // "Not needed" is recorded as a decision, not as a missing row.
        assertThat(offOperation.unitsPerProduct()).isNull();
        assertThat(onOperation.unitsPerProduct()).isEqualTo(3);
        // The catalogue's own value stays beside it, so the override reads as one.
        assertThat(onOperation.unitsPerProductSnapshot()).isEqualTo(2);
    }

    @Test
    @DisplayName("submitting completes the request and closes the answer to further writes")
    void submittingClosesTheAnswer() {
        User requester = newUser("requester");
        User processor = newUser("processor");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        service.assign(requestId, processor.getId(), null);
        asSupervisor();

        var submitted = service.submit(
                requestId, processor.getId(),
                answerFor(service.getDetail(requestId, processor.getId())));

        assertThat(submitted.request().status()).isEqualTo(ProductionOrderScopeRequestStatus.COMPLETED);
        assertThat(submitted.request().resultState()).isEqualTo(ProductionOrderScopeResultState.SUBMITTED);
        assertThat(submitted.request().processedByUserId()).isEqualTo(processor.getId());
        assertThat(submitted.editable()).isFalse();

        ProductionOrderScopeResultRequest again =
                answerFor(service.getDetail(requestId, processor.getId()));
        assertThatThrownBy(() -> service.saveDraft(requestId, processor.getId(), again))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a needed operation without a quantity is refused, and nothing is written")
    void neededOperationsCarryAQuantity() {
        User requester = newUser("requester");
        User processor = newUser("processor");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        service.assign(requestId, processor.getId(), null);
        asSupervisor();

        ProductionOrderScopeResultRequest payload =
                answerFor(service.getDetail(requestId, processor.getId()));
        payload.getItems().get(0).getOperations().get(0).setUnitsPerProduct(null);

        assertThatThrownBy(() -> service.saveDraft(requestId, processor.getId(), payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("količinu u sklopu");

        // The refusal is total: the request did not quietly become a draft.
        assertThat(requestRepository.findById(requestId).orElseThrow().getResultState()).isNull();
    }

    @Test
    @DisplayName("the answer must cover exactly the lines the request covers")
    void theAnswerCoversEveryLine() {
        User requester = newUser("requester");
        User processor = newUser("processor");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje"), aProduct("Varenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        service.assign(requestId, processor.getId(), null);
        asSupervisor();

        ProductionOrderScopeResultRequest payload =
                answerFor(service.getDetail(requestId, processor.getId()));
        payload.setItems(List.of(payload.getItems().get(0)));

        assertThatThrownBy(() -> service.saveDraft(requestId, processor.getId(), payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tačno one stavke");
    }

    @Test
    @DisplayName("an operation belonging to another product cannot be put into the scope")
    void operationsBelongToTheLinesProduct() {
        User requester = newUser("requester");
        User processor = newUser("processor");
        Product other = aProduct("Tuđa operacija");
        ProductionOrder order = anOrder(List.of(aProduct("Sečenje")));
        Long requestId = createForOrder(requester.getId(), order).id();

        service.assign(requestId, processor.getId(), null);
        asSupervisor();

        Long foreignOperationId = operationRepository
                .findByProductIdAndArchivedAtIsNull(other.getId()).get(0).getId();

        ProductionOrderScopeResultRequest payload =
                answerFor(service.getDetail(requestId, processor.getId()));
        payload.getItems().get(0).getOperations().get(0).setOperationId(foreignOperationId);

        assertThatThrownBy(() -> service.saveDraft(requestId, processor.getId(), payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne pripada proizvodu");
    }
}
