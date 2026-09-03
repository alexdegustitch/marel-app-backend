package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationDetailService;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.production_order_progress.OrderProgressService;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ProductProgress;
import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequestService;
import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequestScope;
import com.aleksandarparipovic.marel_app.production_order_scope_request.dto.ProductionOrderScopeRequestCreateRequest;
import com.aleksandarparipovic.marel_app.production_order_scope_request.dto.ProductionOrderScopeRequestDetailResponse;
import com.aleksandarparipovic.marel_app.production_order_scope_request.dto.ProductionOrderScopeResultRequest;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much of an order is done, end to end: a real order, a real agreed scope,
 * real work logs, and the figures read back through the service.
 *
 * <p>{@link OrderProgressCalculatorTest} pins the arithmetic. What this pins is
 * the half a unit test cannot: that the SQL reads the right rows. A draft scope
 * must not count, a withdrawn log must stop counting, and moving a log to
 * another order must move its pieces with it — which is exactly the class of
 * mistake a stored counter would make silently and this design cannot make at
 * all.
 *
 * <p>Transactional, and it has to be. {@link PayrollScenarioFixture} writes
 * compensation schemes and the adjustment-rule matrix behind them, and the
 * database refuses to activate a scheme that has no rule for every ACTIVE
 * adjustment category. Committing those rows would leave the next test class in
 * the shared container facing a matrix it did not build and cannot satisfy —
 * which is exactly what happened: eleven tests of an unrelated class failed the
 * moment this one ran before them. Rolling back is what keeps this class from
 * being felt by any other.
 *
 * <p>Nothing here needs committed state: every figure is read back through a
 * service that joins the test's own transaction.
 */
@Transactional
class OrderProgressIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private OrderProgressService progressService;
    @Autowired private ProductionOrderScopeRequestService scopeService;
    @Autowired private OperationDetailService operationDetailService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private ProductRepository productRepository;
    @Autowired private OperationRepository operationRepository;
    @Autowired private ProductionOrderRepository orderRepository;
    @Autowired private ProductionOrderLineItemRepository lineItemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── The whole way through ───────────────────────────────────────────────

    @Test
    @DisplayName("an order reports the work done and the whole products ready, and they disagree")
    void reportsBothFigures() {
        Product product = aProduct("Krojenje", "Šivenje", "Proba");
        ProductionOrder order = anOrder(product, 10);
        agreeScope(order, Map.of("Krojenje", 1, "Šivenje", 1, "Proba", 2));

        // The owner's own example: 2, 4 and 6 done, of operations needing 1, 1
        // and 2 per assembly, against ten products.
        recordWork(order, operationOf(product, "Krojenje"), 2);
        recordWork(order, operationOf(product, "Šivenje"), 4);
        recordWork(order, operationOf(product, "Proba"), 6);

        OrderProgress progress = progressService.forOrder(order.getId());

        assertThat(progress.scopeDefined()).isTrue();
        ProductProgress line = progress.products().get(0);
        assertThat(line.wholeProductsDone()).isEqualTo(2);
        assertThat(line.percent()).isEqualByComparingTo("20.0");
        assertThat(line.bottleneckOperationName()).isEqualTo("Krojenje");

        // The work itself: 2 + 4 + 6 of 10 + 10 + 20.
        assertThat(progress.requiredPieces()).isEqualTo(40);
        assertThat(progress.donePieces()).isEqualTo(12);
        assertThat(progress.percent()).isEqualByComparingTo("30.0");
    }

    @Test
    @DisplayName("withdrawing a work log takes its pieces back out")
    void withdrawnWorkStopsCounting() {
        Product product = aProduct("Krojenje");
        ProductionOrder order = anOrder(product, 10);
        agreeScope(order, Map.of("Krojenje", 1));
        WorkLog log = recordWork(order, operationOf(product, "Krojenje"), 6);

        assertThat(progressService.forOrder(order.getId()).donePieces()).isEqualTo(6);

        // The soft delete the karton screen performs.
        jdbc.update("UPDATE work_logs SET is_active = false WHERE id = ?", log.getId());

        assertThat(progressService.forOrder(order.getId()).donePieces()).isZero();
        assertThat(progressService.forOrder(order.getId()).percent()).isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("moving a work log to another order moves its pieces with it")
    void movingALogMovesThePieces() {
        Product product = aProduct("Krojenje");
        ProductionOrder from = anOrder(product, 10);
        ProductionOrder to = anOrder(product, 10);
        agreeScope(from, Map.of("Krojenje", 1));
        agreeScope(to, Map.of("Krojenje", 1));
        WorkLog log = recordWork(from, operationOf(product, "Krojenje"), 5);

        assertThat(progressService.forOrder(from.getId()).donePieces()).isEqualTo(5);
        assertThat(progressService.forOrder(to.getId()).donePieces()).isZero();

        jdbc.update("UPDATE work_logs SET production_order_id = ? WHERE id = ?", to.getId(), log.getId());

        assertThat(progressService.forOrder(from.getId()).donePieces()).isZero();
        assertThat(progressService.forOrder(to.getId()).donePieces()).isEqualTo(5);
    }

    @Test
    @DisplayName("a scope still being drafted is not an agreement, and gives no denominator")
    void draftScopeDoesNotCount() {
        Product product = aProduct("Krojenje");
        ProductionOrder order = anOrder(product, 10);
        User processor = newUser("processor");
        Long requestId = requestScope(order);
        scopeService.assign(requestId, processor.getId(), null);
        asSupervisor();
        scopeService.saveDraft(requestId, processor.getId(),
                answerFor(scopeService.getDetail(requestId, processor.getId()), Map.of("Krojenje", 1)));
        recordWork(order, operationOf(product, "Krojenje"), 4);

        OrderProgress progress = progressService.forOrder(order.getId());

        assertThat(progress.scopeDefined()).isFalse();
        assertThat(progress.percent()).isNull();
        assertThat(progress.linesWithoutScope()).isEqualTo(1);
    }

    @Test
    @DisplayName("an order nobody has agreed a scope for says so, rather than reporting nothing done")
    void withoutScopeSaysSo() {
        Product product = aProduct("Krojenje");
        ProductionOrder order = anOrder(product, 10);
        recordWork(order, operationOf(product, "Krojenje"), 4);

        OrderProgress progress = progressService.forOrder(order.getId());

        assertThat(progress.scopeDefined()).isFalse();
        assertThat(progress.percent()).isNull();
        assertThat(progress.products()).isEmpty();
    }

    @Test
    @DisplayName("work on an operation the scope switched off is reported, never counted")
    void workOutsideTheAgreedScopeIsReported() {
        Product product = aProduct("Krojenje", "Ukras");
        ProductionOrder order = anOrder(product, 10);
        // The processor says this variant does not need the decoration.
        agreeScope(order, Map.of("Krojenje", 1));
        recordWork(order, operationOf(product, "Krojenje"), 4);
        recordWork(order, operationOf(product, "Ukras"), 25);

        OrderProgress progress = progressService.forOrder(order.getId());

        assertThat(progress.donePieces()).isEqualTo(4);
        assertThat(progress.percent()).isEqualByComparingTo("40.0");
        assertThat(progress.outOfScope()).singleElement().satisfies(stray -> {
            assertThat(stray.operationName()).isEqualTo("Ukras");
            assertThat(stray.donePieces()).isEqualTo(25);
            assertThat(stray.productName()).isEqualTo(product.getProductName());
        });
    }

    @Test
    @DisplayName("several orders are answered in one go, each with its own figure")
    void manyOrdersAtOnce() {
        Product product = aProduct("Krojenje");
        ProductionOrder done = anOrder(product, 10);
        ProductionOrder started = anOrder(product, 10);
        ProductionOrder untouched = anOrder(product, 10);
        agreeScope(done, Map.of("Krojenje", 1));
        agreeScope(started, Map.of("Krojenje", 1));
        recordWork(done, operationOf(product, "Krojenje"), 10);
        recordWork(started, operationOf(product, "Krojenje"), 3);

        var summaries = progressService.summaries(
                List.of(done.getId(), started.getId(), untouched.getId()));

        assertThat(summaries).containsOnlyKeys(done.getId(), started.getId(), untouched.getId());
        assertThat(summaries.get(done.getId()).percent()).isEqualByComparingTo("100.0");
        assertThat(summaries.get(done.getId()).wholeProductsDone()).isEqualTo(10);
        assertThat(summaries.get(started.getId()).percent()).isEqualByComparingTo("30.0");
        assertThat(summaries.get(untouched.getId()).scopeDefined()).isFalse();
        assertThat(summaries.get(untouched.getId()).percent()).isNull();
    }

    // ── The operation detail page ───────────────────────────────────────────

    @Test
    @DisplayName("the operation page measures against the order's agreed scope, not the catalogue")
    void operationPagePrefersTheAgreedScope() {
        // The catalogue says two per assembly; this order's razrada says five.
        Product product = aProduct("Krojenje");
        Operation operation = operationOf(product, "Krojenje");
        ProductionOrder agreed = anOrder(product, 10);
        ProductionOrder notAgreed = anOrder(product, 10);
        agreeScope(agreed, Map.of("Krojenje", 5));
        recordWork(agreed, operation, 20);

        var rows = operationDetailService.getProductionOrders(operation.getId());

        var withScope = rows.stream()
                .filter(row -> row.orderId().equals(agreed.getId())).findFirst().orElseThrow();
        assertThat(withScope.requiredPieces()).isEqualTo(50);
        assertThat(withScope.donePieces()).isEqualTo(20);
        assertThat(withScope.requirementFromScope()).isTrue();

        // With no agreement, the catalogue still answers, and says that it did.
        var withoutScope = rows.stream()
                .filter(row -> row.orderId().equals(notAgreed.getId())).findFirst().orElseThrow();
        assertThat(withoutScope.requiredPieces()).isEqualTo(20);
        assertThat(withoutScope.requirementFromScope()).isFalse();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private Product aProduct(String... operationNames) {
        Product product = new Product();
        product.setProductName("Progres proizvod " + COUNTER.incrementAndGet());
        product.setActive(true);
        Product saved = productRepository.save(product);

        for (String name : operationNames) {
            Operation operation = new Operation();
            operation.setProduct(saved);
            operation.setOpName(name);
            // The catalogue's own figure, deliberately different from what the
            // scopes below agree, so the two can be told apart.
            operation.setUnitsPerProduct(2);
            operation.setNormRequired(false);
            operationRepository.save(operation);
        }
        return saved;
    }

    private Operation operationOf(Product product, String name) {
        return operationRepository.findByProductIdAndArchivedAtIsNull(product.getId()).stream()
                .filter(operation -> name.equals(operation.getOpName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No operation " + name));
    }

    private ProductionOrder anOrder(Product product, int quantity) {
        int n = COUNTER.incrementAndGet();
        ProductionOrder order = orderRepository.save(ProductionOrder.builder()
                .code("PROG-" + n + "-" + System.nanoTime())
                .name("Nalog " + n)
                .status(ProductionOrderStatus.CREATED)
                .testingRequired(false)
                .isHighPriority(false)
                .isAnnounced(false)
                .hasSuccessiveDeliveries(false)
                .isActive(true)
                .build());

        ProductionOrderLineItem item = new ProductionOrderLineItem();
        item.setProductionOrder(order);
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setLineOrder(1);
        item.setIsActive(true);
        lineItemRepository.save(item);
        return order;
    }

    /** Raises a scope request, answers it with the given quantities, and submits. */
    private void agreeScope(ProductionOrder order, Map<String, Integer> unitsByOperation) {
        User processor = newUser("processor");
        Long requestId = requestScope(order);
        scopeService.assign(requestId, processor.getId(), null);
        asSupervisor();
        scopeService.submit(requestId, processor.getId(),
                answerFor(scopeService.getDetail(requestId, processor.getId()), unitsByOperation));
        SecurityContextHolder.clearContext();
    }

    private Long requestScope(ProductionOrder order) {
        ProductionOrderScopeRequestCreateRequest request = new ProductionOrderScopeRequestCreateRequest();
        request.setProductionOrderId(order.getId());
        request.setScope(ProductionOrderScopeRequestScope.ORDER);
        return scopeService.create(request, newUser("requester").getId()).id();
    }

    /**
     * An answer that keeps the named operations at the given quantity per
     * assembly and switches every other one off.
     */
    private ProductionOrderScopeResultRequest answerFor(
            ProductionOrderScopeRequestDetailResponse detail, Map<String, Integer> unitsByOperation) {

        ProductionOrderScopeResultRequest payload = new ProductionOrderScopeResultRequest();
        payload.setItems(detail.items().stream().map(item -> {
            ProductionOrderScopeResultRequest.Item answer = new ProductionOrderScopeResultRequest.Item();
            answer.setItemId(item.line().itemId());
            answer.setOperations(item.operations().stream().map(operation -> {
                ProductionOrderScopeResultRequest.Operation decided =
                        new ProductionOrderScopeResultRequest.Operation();
                Integer units = unitsByOperation.get(operation.operationName());
                decided.setOperationId(operation.operationId());
                decided.setNeeded(units != null);
                decided.setUnitsPerProduct(units);
                return decided;
            }).toList());
            return answer;
        }).toList());
        return payload;
    }

    /** One work log against this order and operation, with the pieces on it. */
    private WorkLog recordWork(ProductionOrder order, Operation operation, int quantity) {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        WorkShift shift = fixture.workShift(
                scenario.employee(), LocalDate.of(2031, 1, 1 + (COUNTER.incrementAndGet() % 27)), 6, 480);
        WorkLog log = fixture.workLog(shift, operation, scenario.workCategory(), 0, 60, quantity);
        jdbc.update("UPDATE work_logs SET production_order_id = ? WHERE id = ?", order.getId(), log.getId());
        return log;
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

    private void asSupervisor() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "supervisor", "n/a", List.of(new SimpleGrantedAuthority("ROLE_supervisor"))));
    }
}
