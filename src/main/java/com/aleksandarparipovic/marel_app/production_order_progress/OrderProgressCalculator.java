package com.aleksandarparipovic.marel_app.production_order_progress;

import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationOutputRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationRef;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OutOfScopeWork;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ProductProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ScopeRequirementRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns "what the order agreed to" and "what was recorded" into the two figures
 * a dispatcher asks for: how much of the work is done, and how many whole
 * products could go out of the door.
 *
 * <p>No Spring, no database, no clock. Everything it needs arrives as arguments,
 * so every rule below is stated once and can be read back from a test.
 *
 * <p><b>The rules, and where they come from.</b> All three were decided by the
 * owner; none is invented here.
 *
 * <ol>
 *   <li>A product is finished as many times as its SLOWEST needed operation
 *       allows. Four of an operation needed twice per assembly is two products.
 *   <li>The order's own figure is the work, not the products:
 *       {@code Σ min(done, required) ÷ Σ required}. Each operation is capped at
 *       what was asked for, so overproducing one cannot carry the order past
 *       what the rest have actually done.
 *   <li>Scrap is recorded beside the pieces and does not reduce them, exactly as
 *       everywhere else in this application.
 * </ol>
 */
public final class OrderProgressCalculator {

    private OrderProgressCalculator() {
    }

    /**
     * @param requirements          the SUBMITTED scope, one row per needed operation per line
     * @param output                pieces recorded per order and operation
     * @param operations            names for operations found outside the scope
     * @param linesWithoutScope     how many lines of each order have no agreed scope
     * @param orderIds              every order to answer for, including ones with neither
     * @return one entry per requested order, never null and never missing an id
     */
    public static Map<Long, OrderProgress> calculate(
            Collection<? extends ScopeRequirementRow> requirements,
            Collection<? extends OperationOutputRow> output,
            Map<Long, OperationRef> operations,
            Map<Long, Integer> linesWithoutScope,
            Collection<Long> orderIds) {

        Map<Long, Map<Long, Long>> doneByOrder = new HashMap<>();
        Map<Long, Map<Long, Long>> scrapByOrder = new HashMap<>();
        for (OperationOutputRow row : output) {
            doneByOrder.computeIfAbsent(row.getOrderId(), k -> new HashMap<>())
                    .merge(row.getOperationId(), value(row.getDonePieces()), Long::sum);
            scrapByOrder.computeIfAbsent(row.getOrderId(), k -> new HashMap<>())
                    .merge(row.getOperationId(), value(row.getScrapPieces()), Long::sum);
        }

        Map<Long, List<ScopeRequirementRow>> byOrder = new LinkedHashMap<>();
        for (ScopeRequirementRow row : requirements) {
            byOrder.computeIfAbsent(row.getOrderId(), k -> new ArrayList<>()).add(row);
        }

        Map<Long, OrderProgress> result = new LinkedHashMap<>();
        for (Long orderId : orderIds) {
            List<ScopeRequirementRow> rows = byOrder.get(orderId);
            int missing = linesWithoutScope.getOrDefault(orderId, 0);
            if (rows == null || rows.isEmpty()) {
                result.put(orderId, OrderProgress.withoutScope(orderId, missing));
                continue;
            }
            result.put(orderId, forOneOrder(
                    orderId,
                    rows,
                    doneByOrder.getOrDefault(orderId, Map.of()),
                    scrapByOrder.getOrDefault(orderId, Map.of()),
                    operations,
                    missing));
        }
        return result;
    }

    private static OrderProgress forOneOrder(
            Long orderId,
            List<ScopeRequirementRow> rows,
            Map<Long, Long> done,
            Map<Long, Long> scrap,
            Map<Long, OperationRef> operations,
            int linesWithoutScope) {

        // Grouped by product, because that is the grain the data distinguishes:
        // a work log names an order and an operation, and an operation belongs to
        // exactly one product. Which lines that covers is reported alongside.
        Map<Long, ProductGroup> groups = new LinkedHashMap<>();
        for (ScopeRequirementRow row : rows) {
            groups.computeIfAbsent(row.getProductId(), id -> new ProductGroup(id, row.getProductName()))
                    .add(row);
        }

        List<ProductProgress> products = new ArrayList<>(groups.size());
        long requiredTotal = 0;
        long doneTotal = 0;
        long recordedTotal = 0;
        long scrapTotal = 0;
        Set<Long> inScope = new HashSet<>();

        for (ProductGroup group : groups.values()) {
            ProductProgress product = group.toProgress(done, scrap);
            products.add(product);
            for (OperationProgress operation : product.operations()) {
                inScope.add(operation.operationId());
                requiredTotal += operation.requiredPieces();
                doneTotal += operation.countedPieces();
                recordedTotal += operation.donePieces();
                scrapTotal += operation.scrapPieces();
            }
        }

        List<OutOfScopeWork> outOfScope = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : done.entrySet()) {
            if (inScope.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            OperationRef ref = operations.get(entry.getKey());
            outOfScope.add(new OutOfScopeWork(
                    entry.getKey(),
                    ref == null ? null : ref.operationName(),
                    ref == null ? null : ref.productId(),
                    ref == null ? null : ref.productName(),
                    entry.getValue()));
        }
        outOfScope.sort(Comparator.comparing(OutOfScopeWork::donePieces).reversed());

        return new OrderProgress(
                orderId,
                true,
                groups.values().stream().mapToInt(g -> g.lineItemIds.size()).sum(),
                linesWithoutScope,
                requiredTotal,
                doneTotal,
                recordedTotal,
                scrapTotal,
                percent(doneTotal, requiredTotal),
                products,
                outOfScope);
    }

    /** One product of one order, gathering the lines and operations that belong to it. */
    private static final class ProductGroup {
        private final Long productId;
        private final String productName;
        private final Set<Long> lineItemIds = new LinkedHashSet<>();
        private final Map<Long, Integer> quantityByLine = new LinkedHashMap<>();
        private final Map<Long, OperationRequirement> byOperation = new LinkedHashMap<>();

        private ProductGroup(Long productId, String productName) {
            this.productId = productId;
            this.productName = productName;
        }

        private void add(ScopeRequirementRow row) {
            lineItemIds.add(row.getLineItemId());
            // Per line, not per row: a line contributes its quantity once, however
            // many operations it needs.
            quantityByLine.put(row.getLineItemId(), value(row.getLineQuantity()).intValue());
            byOperation
                    .computeIfAbsent(row.getOperationId(),
                            id -> new OperationRequirement(id, row.getOperationName()))
                    .add(value(row.getLineQuantity()) * value(row.getUnitsPerProduct()));
        }

        private ProductProgress toProgress(Map<Long, Long> done, Map<Long, Long> scrap) {
            long requiredProducts = quantityByLine.values().stream().mapToLong(Integer::longValue).sum();

            List<OperationProgress> operations = new ArrayList<>(byOperation.size());
            for (OperationRequirement requirement : byOperation.values()) {
                operations.add(requirement.toProgress(
                        requiredProducts,
                        done.getOrDefault(requirement.operationId, 0L),
                        scrap.getOrDefault(requirement.operationId, 0L)));
            }

            long whole = 0;
            String bottleneck = null;
            Long bottleneckId = null;
            if (!operations.isEmpty()) {
                whole = Long.MAX_VALUE;
                for (OperationProgress operation : operations) {
                    long supported = operation.wholeProductsSupported(requiredProducts);
                    if (supported < whole) {
                        whole = supported;
                        bottleneck = operation.operationName();
                        bottleneckId = operation.operationId();
                    }
                }
                // A product cannot be finished more times than it was ordered.
                whole = Math.min(whole, requiredProducts);
            }
            if (whole >= requiredProducts) {
                bottleneck = null;
                bottleneckId = null;
            }

            return new ProductProgress(
                    productId,
                    productName,
                    List.copyOf(lineItemIds),
                    requiredProducts,
                    operations.isEmpty() ? 0 : whole,
                    operations.isEmpty() ? null : percent(whole, requiredProducts),
                    bottleneckId,
                    bottleneck,
                    operations);
        }
    }

    /** One operation of one product, summed over every line that needs it. */
    private static final class OperationRequirement {
        private final Long operationId;
        private final String operationName;
        private long requiredPieces;

        private OperationRequirement(Long operationId, String operationName) {
            this.operationId = operationId;
            this.operationName = operationName;
        }

        private void add(long pieces) {
            requiredPieces += pieces;
        }

        private OperationProgress toProgress(long requiredProducts, long done, long scrap) {
            BigDecimal unitsPerProduct = requiredProducts <= 0
                    ? null
                    : BigDecimal.valueOf(requiredPieces)
                            .divide(BigDecimal.valueOf(requiredProducts), 2, RoundingMode.HALF_UP)
                            .stripTrailingZeros();
            return new OperationProgress(
                    operationId, operationName, unitsPerProduct, requiredPieces, done, scrap);
        }
    }

    /** Truncated rather than rounded: 99.99 % done is not 100 % done. */
    private static BigDecimal percent(long done, long required) {
        if (required <= 0) {
            return null;
        }
        return BigDecimal.valueOf(Math.min(done, required))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(required), 1, RoundingMode.DOWN);
    }

    private static Long value(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
