package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.production_order_progress.OrderProgressCalculator;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationOutputRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationRef;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ProductProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ScopeRequirementRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two figures an order carries, and the rules behind them.
 *
 * <p>Every rule here was decided by the owner, and the first two tests are their
 * own worked examples, kept verbatim so a later change that quietly redefines
 * "done" fails against the sentence it was agreed in.
 */
class OrderProgressCalculatorTest {

    private static final Long ORDER = 7L;
    private static final Long PRODUCT = 42L;
    private static final Long LINE = 100L;

    // ── The owner's two examples ────────────────────────────────────────────

    @Test
    @DisplayName("a product is finished as many times as its slowest operation allows")
    void wholeProductsFollowTheSlowestOperation() {
        // "operacije x1, x2, x3 imaju 1, 1, 2 u sklopu, urađeno je 2, 4, 6,
        //  treba nam 10 ovakvih proizvoda" → 2 cela proizvoda, 20 %.
        OrderProgress progress = calculate(
                List.of(requirement("x1", 1, 1, 10),
                        requirement("x2", 2, 1, 10),
                        requirement("x3", 3, 2, 10)),
                List.of(output(1, 2), output(2, 4), output(3, 6)));

        ProductProgress product = progress.products().get(0);
        assertThat(product.wholeProductsDone()).isEqualTo(2);
        assertThat(product.requiredProducts()).isEqualTo(10);
        assertThat(product.percent()).isEqualByComparingTo("20.0");
        assertThat(product.bottleneckOperationName()).isEqualTo("x1");
        // The id travels with the name so the screen can link to the operation.
        assertThat(product.bottleneckOperationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the order's own figure is the work: (3+5+7) of (10+5+15) is 50 %")
    void orderPercentIsTheWorkNotTheProducts() {
        // Five products; the three operations take 2, 1 and 3 each, so the order
        // asks for 10, 5 and 15 pieces. Done: 3, 5, 7.
        OrderProgress progress = calculate(
                List.of(requirement("x1", 1, 2, 5),
                        requirement("x2", 2, 1, 5),
                        requirement("x3", 3, 3, 5)),
                List.of(output(1, 3), output(2, 5), output(3, 7)));

        assertThat(progress.requiredPieces()).isEqualTo(30);
        assertThat(progress.donePieces()).isEqualTo(15);
        assertThat(progress.percent()).isEqualByComparingTo("50.0");

        // And the other question, which disagrees on purpose: half the work is
        // done, one whole product exists.
        assertThat(progress.products().get(0).wholeProductsDone()).isEqualTo(1);
        assertThat(progress.products().get(0).percent()).isEqualByComparingTo("20.0");
    }

    // ── The rules the owner chose ───────────────────────────────────────────

    @Nested
    @DisplayName("overproducing one operation")
    class Overproduction {

        @Test
        @DisplayName("cannot carry the order past what the others have done")
        void isCappedAtWhatWasAsked() {
            OrderProgress progress = calculate(
                    List.of(requirement("x1", 1, 2, 5),
                            requirement("x2", 2, 1, 5),
                            requirement("x3", 3, 3, 5)),
                    List.of(output(1, 30)));

            // Without the cap this would read 100 % with nothing shippable.
            assertThat(progress.donePieces()).isEqualTo(10);
            assertThat(progress.percent()).isEqualByComparingTo("33.3");
            assertThat(progress.products().get(0).wholeProductsDone()).isZero();
        }

        @Test
        @DisplayName("is still reported, so nobody has to wonder where the pieces went")
        void isReportedAsRecorded() {
            OrderProgress progress = calculate(
                    List.of(requirement("x1", 1, 2, 5)),
                    List.of(output(1, 30)));

            assertThat(progress.recordedPieces()).isEqualTo(30);
            assertThat(progress.donePieces()).isEqualTo(10);
            OperationProgress operation = progress.products().get(0).operations().get(0);
            assertThat(operation.overproduced()).isTrue();
            assertThat(operation.percent()).isEqualByComparingTo("100.0");
        }
    }

    @Test
    @DisplayName("scrap is recorded beside the pieces and does not reduce them")
    void scrapDoesNotReduceProgress() {
        OrderProgress progress = calculate(
                List.of(requirement("x1", 1, 1, 10)),
                List.of(output(1, 6, 4)));

        assertThat(progress.donePieces()).isEqualTo(6);
        assertThat(progress.scrapPieces()).isEqualTo(4);
        assertThat(progress.products().get(0).wholeProductsDone()).isEqualTo(6);
    }

    @Test
    @DisplayName("no agreed scope is not zero progress — it is no answer at all")
    void withoutScopeThePercentIsAbsent() {
        Map<Long, OrderProgress> progress = OrderProgressCalculator.calculate(
                List.of(), List.of(output(1, 500)), Map.of(), Map.of(ORDER, 3), List.of(ORDER));

        OrderProgress order = progress.get(ORDER);
        assertThat(order.scopeDefined()).isFalse();
        assertThat(order.percent()).isNull();
        assertThat(order.linesWithoutScope()).isEqualTo(3);
        assertThat(order.products()).isEmpty();
    }

    @Test
    @DisplayName("work on an operation the scope does not ask for is reported, never counted")
    void workOutsideTheScopeIsReported() {
        OrderProgress progress = OrderProgressCalculator.calculate(
                List.of(requirement("x1", 1, 1, 10)),
                List.of(output(1, 4), output(99, 250)),
                Map.of(99L, new OperationRef(99L, "Nešto drugo", 8L, "Drugi proizvod")),
                Map.of(),
                List.of(ORDER)).get(ORDER);

        assertThat(progress.donePieces()).isEqualTo(4);
        assertThat(progress.percent()).isEqualByComparingTo("40.0");
        assertThat(progress.outOfScope()).singleElement().satisfies(stray -> {
            assertThat(stray.operationId()).isEqualTo(99L);
            assertThat(stray.operationName()).isEqualTo("Nešto drugo");
            assertThat(stray.donePieces()).isEqualTo(250);
        });
    }

    @Test
    @DisplayName("a finished product names no bottleneck and stops at 100 %")
    void finishedProductIsFinished() {
        OrderProgress progress = calculate(
                List.of(requirement("x1", 1, 1, 10), requirement("x2", 2, 2, 10)),
                List.of(output(1, 10), output(2, 20)));

        ProductProgress product = progress.products().get(0);
        assertThat(product.wholeProductsDone()).isEqualTo(10);
        assertThat(product.percent()).isEqualByComparingTo("100.0");
        assertThat(product.bottleneckOperationName()).isNull();
        assertThat(product.bottleneckOperationId()).isNull();
        assertThat(progress.percent()).isEqualByComparingTo("100.0");
    }

    @Test
    @DisplayName("an operation nobody has touched leaves the product at zero, whatever the rest did")
    void oneUntouchedOperationHoldsTheProductBack() {
        OrderProgress progress = calculate(
                List.of(requirement("x1", 1, 1, 10), requirement("x2", 2, 1, 10)),
                List.of(output(1, 10)));

        assertThat(progress.products().get(0).wholeProductsDone()).isZero();
        assertThat(progress.products().get(0).bottleneckOperationName()).isEqualTo("x2");
        assertThat(progress.products().get(0).bottleneckOperationId()).isEqualTo(2L);
        // Half the work is genuinely done, and the order says so.
        assertThat(progress.percent()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("the percentage is truncated: nearly finished is not finished")
    void percentIsTruncatedNotRounded() {
        OrderProgress progress = calculate(
                List.of(requirement("x1", 1, 1, 1000)),
                List.of(output(1, 999)));

        assertThat(progress.percent()).isEqualByComparingTo("99.9");
    }

    @Test
    @DisplayName("every order asked about gets an answer, including one with nothing on it")
    void everyRequestedOrderIsAnswered() {
        Map<Long, OrderProgress> progress = OrderProgressCalculator.calculate(
                List.of(), List.of(), Map.of(), Map.of(), List.of(1L, 2L, 3L));

        assertThat(progress).containsOnlyKeys(1L, 2L, 3L);
        assertThat(progress.get(2L).scopeDefined()).isFalse();
    }

    @Test
    @DisplayName("two lines of the same product are one product group, with their quantities added")
    void twoLinesOfOneProductAreCountedTogether() {
        // Work is recorded against an order and an operation, so two lines of the
        // same product cannot be told apart. They are reported as one group
        // rather than guessed at.
        OrderProgress progress = calculate(
                List.of(requirement(LINE, "x1", 1, 1, 10),
                        requirement(LINE + 1, "x1", 1, 1, 30)),
                List.of(output(1, 12)));

        assertThat(progress.products()).hasSize(1);
        ProductProgress product = progress.products().get(0);
        assertThat(product.lineItemIds()).containsExactly(LINE, LINE + 1);
        assertThat(product.requiredProducts()).isEqualTo(40);
        assertThat(product.wholeProductsDone()).isEqualTo(12);
        assertThat(progress.requiredPieces()).isEqualTo(40);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static OrderProgress calculate(
            List<ScopeRequirementRow> requirements, List<OperationOutputRow> output) {
        return OrderProgressCalculator
                .calculate(requirements, output, Map.of(), Map.of(), List.of(ORDER))
                .get(ORDER);
    }

    private static ScopeRequirementRow requirement(
            String operationName, long operationId, int unitsPerProduct, int lineQuantity) {
        return requirement(LINE, operationName, operationId, unitsPerProduct, lineQuantity);
    }

    private static ScopeRequirementRow requirement(
            Long lineId, String operationName, long operationId, int unitsPerProduct, int lineQuantity) {
        return new Requirement(lineId, operationId, operationName, unitsPerProduct, lineQuantity);
    }

    private static OperationOutputRow output(long operationId, long done) {
        return output(operationId, done, 0);
    }

    private static OperationOutputRow output(long operationId, long done, long scrap) {
        return new Output(operationId, done, scrap);
    }

    private record Requirement(
            Long lineId, Long operationId, String operationName, Integer units, Integer quantity)
            implements ScopeRequirementRow {

        Requirement(Long lineId, long operationId, String operationName, int units, int quantity) {
            this(lineId, Long.valueOf(operationId), operationName, Integer.valueOf(units), Integer.valueOf(quantity));
        }

        @Override public Long getOrderId() { return ORDER; }
        @Override public Long getLineItemId() { return lineId; }
        @Override public Long getProductId() { return PRODUCT; }
        @Override public String getProductName() { return "Proizvod"; }
        @Override public Long getOperationId() { return operationId; }
        @Override public String getOperationName() { return operationName; }
        @Override public Integer getUnitsPerProduct() { return units; }
        @Override public Integer getLineQuantity() { return quantity; }
    }

    private record Output(Long operationId, Long done, Long scrap) implements OperationOutputRow {

        Output(long operationId, long done, long scrap) {
            this(Long.valueOf(operationId), Long.valueOf(done), Long.valueOf(scrap));
        }

        @Override public Long getOrderId() { return ORDER; }
        @Override public Long getOperationId() { return operationId; }
        @Override public Long getDonePieces() { return done; }
        @Override public Long getScrapPieces() { return scrap; }
    }
}
