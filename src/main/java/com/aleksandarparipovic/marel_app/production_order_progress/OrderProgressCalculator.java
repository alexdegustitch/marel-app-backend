package com.aleksandarparipovic.marel_app.production_order_progress;

import com.aleksandarparipovic.marel_app.production_order_progress.dto.LineProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationKey;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns "what the order agreed to" and "what was recorded" into the TWO
 * figures a dispatcher asks for (SPEC 5.6):
 *
 * <ol>
 *   <li><b>Napredak rada</b> — how much of the work is done, ONE exact number:
 *       {@code Σ min(done, required) ÷ Σ required} per key. Under the shared
 *       allocation model (a line is credited at most its own need) this does
 *       not depend on whose pieces they were, so there is no range here.
 *   <li><b>Gotovi proizvodi</b> — how many whole products could ship, a RANGE:
 *       a work log names an operation, not a line, so when an operation is
 *       shared between lines (product-level, or bound to a value both
 *       variants carry) the split is unknown. The range's ends are computed
 *       by EXACT search over the allowed allocations — never a greedy guess
 *       (SPEC R2).
 * </ol>
 *
 * <p>The aggregation KEY is {@code COALESCE(operation group, operation)}:
 * interchangeable machines pool their work, so razrada naming the new press
 * still counts the pieces made on the old one (SPEC 5.2).
 *
 * <p>No Spring, no database, no clock. Everything arrives as arguments, so
 * every rule is stated once and read back from {@code OrderProgressCalculatorTest},
 * where the SPEC's worked example (rad 46,7 %, gotovo 10–30 od 150) is pinned.
 */
public final class OrderProgressCalculator {

    /**
     * The exact searches are tiny in real data (a product sits on a handful of
     * lines). These stops are parachutes, not expectations; past them the
     * bounds DEGRADE SOFTLY to still-valid, merely looser ones.
     */
    static final long MAX_SEARCH_NODES = 1_000_000;

    private OrderProgressCalculator() {
    }

    /**
     * @param requirements      the SUBMITTED scope, one row per needed operation per line
     * @param output            pieces recorded per order and operation
     * @param operationKeys     each seen operation's group, for the COALESCE key
     * @param operations        names for operations found outside the scope
     * @param linesWithoutScope how many lines of each order have no agreed scope
     * @param orderIds          every order to answer for, including ones with neither
     * @return one entry per requested order, never null and never missing an id
     */
    public static Map<Long, OrderProgress> calculate(
            Collection<? extends ScopeRequirementRow> requirements,
            Collection<? extends OperationOutputRow> output,
            Map<Long, OperationKey> operationKeys,
            Map<Long, OperationRef> operations,
            Map<Long, Integer> linesWithoutScope,
            Collection<Long> orderIds) {

        // Work logs land on their KEY: the group when the operation is in one.
        Map<Long, Map<KeyId, Long>> doneByOrder = new HashMap<>();
        Map<Long, Map<KeyId, Long>> scrapByOrder = new HashMap<>();
        Map<Long, Map<Long, Long>> doneByOperation = new HashMap<>();
        for (OperationOutputRow row : output) {
            KeyId key = keyOf(row.getOperationId(), operationKeys);
            doneByOrder.computeIfAbsent(row.getOrderId(), k -> new HashMap<>())
                    .merge(key, value(row.getDonePieces()), Long::sum);
            scrapByOrder.computeIfAbsent(row.getOrderId(), k -> new HashMap<>())
                    .merge(key, value(row.getScrapPieces()), Long::sum);
            doneByOperation.computeIfAbsent(row.getOrderId(), k -> new HashMap<>())
                    .merge(row.getOperationId(), value(row.getDonePieces()), Long::sum);
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
                    doneByOperation.getOrDefault(orderId, Map.of()),
                    operationKeys,
                    operations,
                    missing));
        }
        return result;
    }

    private static OrderProgress forOneOrder(
            Long orderId,
            List<ScopeRequirementRow> rows,
            Map<KeyId, Long> done,
            Map<KeyId, Long> scrap,
            Map<Long, Long> doneByOperation,
            Map<Long, OperationKey> operationKeys,
            Map<Long, OperationRef> operations,
            int linesWithoutScope) {

        // Grouped by product, the grain the data distinguishes; inside, by KEY
        // and by LINE — the two directions the range question runs along.
        Map<Long, ProductGroup> groups = new LinkedHashMap<>();
        for (ScopeRequirementRow row : rows) {
            groups.computeIfAbsent(row.getProductId(), id -> new ProductGroup(id, row.getProductName()))
                    .add(row, operationKeys);
        }

        List<ProductProgress> products = new ArrayList<>(groups.size());
        long requiredTotal = 0;
        long doneTotal = 0;
        long recordedTotal = 0;
        long scrapTotal = 0;
        Set<Long> inScopeOperations = new HashSet<>();
        Set<KeyId> inScopeKeys = new HashSet<>();

        for (ProductGroup group : groups.values()) {
            ProductProgress product = group.toProgress(done, scrap);
            products.add(product);
            inScopeOperations.addAll(group.memberOperationIds);
            inScopeKeys.addAll(group.byKey.keySet());
            for (OperationProgress operation : product.operations()) {
                requiredTotal += operation.requiredPieces();
                doneTotal += operation.countedPieces();
                recordedTotal += operation.donePieces();
                scrapTotal += operation.scrapPieces();
            }
        }

        // Out of scope: work on an operation whose KEY the scope never asked
        // for. Work on a non-named GROUP MEMBER is in scope through its key —
        // the old press counts — so it must not land here as a stray.
        List<OutOfScopeWork> outOfScope = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : doneByOperation.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (inScopeOperations.contains(entry.getKey())
                    || inScopeKeys.contains(keyOf(entry.getKey(), operationKeys))) {
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
                groups.values().stream().mapToInt(g -> g.lines.size()).sum(),
                linesWithoutScope,
                requiredTotal,
                doneTotal,
                recordedTotal,
                scrapTotal,
                percent(doneTotal, requiredTotal),
                products,
                outOfScope);
    }

    private static KeyId keyOf(Long operationId, Map<Long, OperationKey> operationKeys) {
        OperationKey key = operationKeys.get(operationId);
        if (key != null && key.operationGroupId() != null) {
            return new KeyId(null, key.operationGroupId());
        }
        return new KeyId(operationId, null);
    }

    /** COALESCE(group, operation) as an identity. Exactly one side is set. */
    record KeyId(Long operationId, Long groupId) {
    }

    /** One product of one order: its lines, and its requirements per key per line. */
    private static final class ProductGroup {
        private final Long productId;
        private final String productName;
        private final Map<Long, LineInfo> lines = new LinkedHashMap<>();
        private final Map<KeyId, KeyAgg> byKey = new LinkedHashMap<>();
        private final Set<Long> memberOperationIds = new LinkedHashSet<>();

        private ProductGroup(Long productId, String productName) {
            this.productId = productId;
            this.productName = productName;
        }

        private void add(ScopeRequirementRow row, Map<Long, OperationKey> operationKeys) {
            memberOperationIds.add(row.getOperationId());
            lines.computeIfAbsent(row.getLineItemId(), id -> new LineInfo(
                    id, value(row.getLineQuantity()), row.getProductVariantId(), row.getVariantLabel()));

            KeyId keyId = keyOf(row.getOperationId(), operationKeys);
            KeyAgg agg = byKey.computeIfAbsent(keyId, k -> {
                OperationKey key = operationKeys.get(row.getOperationId());
                boolean grouped = key != null && key.operationGroupId() != null;
                return new KeyAgg(
                        row.getOperationId(),
                        grouped ? key.operationGroupName() : null,
                        grouped ? key.operationGroupId() : null,
                        row.getOperationName());
            });
            long units = value(row.getUnitsPerProduct());
            long needed = value(row.getLineQuantity()) * units;
            // Razrada may name the same key twice for one line (two members of
            // one group written out) — their needs merge rather than multiply.
            agg.perLine.merge(row.getLineItemId(), new LineNeed(needed, units), LineNeed::mergeMax);
        }

        private ProductProgress toProgress(Map<KeyId, Long> doneByKey, Map<KeyId, Long> scrapByKey) {
            long requiredProducts = lines.values().stream().mapToLong(l -> l.quantity).sum();

            List<OperationProgress> operations = new ArrayList<>(byKey.size());
            Map<KeyId, Long> allowed = new LinkedHashMap<>(); // S_k = min(D_k, R_k)
            for (Map.Entry<KeyId, KeyAgg> entry : byKey.entrySet()) {
                KeyAgg agg = entry.getValue();
                long required = agg.requiredPieces();
                long donePieces = doneByKey.getOrDefault(entry.getKey(), 0L);
                long scrapPieces = scrapByKey.getOrDefault(entry.getKey(), 0L);
                allowed.put(entry.getKey(), Math.min(donePieces, required));

                BigDecimal unitsPerProduct = requiredProducts <= 0
                        ? null
                        : BigDecimal.valueOf(required)
                                .divide(BigDecimal.valueOf(requiredProducts), 2, RoundingMode.HALF_UP)
                                .stripTrailingZeros();
                operations.add(new OperationProgress(
                        agg.representativeOperationId,
                        agg.groupName != null ? agg.groupName : agg.operationName,
                        agg.groupId,
                        agg.groupName,
                        unitsPerProduct,
                        required,
                        donePieces,
                        scrapPieces));
            }

            // ── mera 2: the finished-goods range (SPEC 5.6) ──────────────────
            List<LineInfo> lineList = new ArrayList<>(lines.values());
            List<LineProgress> lineRanges = new ArrayList<>(lineList.size());
            for (LineInfo line : lineList) {
                long maxForLine = line.quantity;
                long minForLine = line.quantity;
                boolean hasKey = false;
                for (Map.Entry<KeyId, KeyAgg> entry : byKey.entrySet()) {
                    LineNeed need = entry.getValue().perLine.get(line.lineItemId);
                    if (need == null || need.units <= 0) {
                        continue;
                    }
                    hasKey = true;
                    long s = allowed.get(entry.getKey());
                    long othersNeed = entry.getValue().requiredPieces() - need.needed;
                    maxForLine = Math.min(maxForLine, Math.min(s, need.needed) / need.units);
                    minForLine = Math.min(minForLine, Math.max(0, s - othersNeed) / need.units);
                }
                if (!hasKey) {
                    maxForLine = 0;
                    minForLine = 0;
                }
                lineRanges.add(new LineProgress(
                        line.lineItemId, line.variantId, line.variantLabel,
                        line.quantity, minForLine, maxForLine));
            }

            long totalMax = jointMaximum(lineList, byKey, allowed);
            long totalMin = jointMinimum(lineList, byKey, allowed);
            totalMax = Math.min(totalMax, requiredProducts);
            totalMin = Math.min(totalMin, totalMax);

            // The bottleneck to NAME: the key whose relaxation would raise the
            // range's top end the most — what a dispatcher should chase. A key
            // that only caps one already-small line is binding, but it is not
            // THE bottleneck; the counterfactual tells them apart.
            String bottleneck = null;
            Long bottleneckId = null;
            if (!byKey.isEmpty() && totalMax < requiredProducts) {
                long bestGain = -1;
                for (Map.Entry<KeyId, KeyAgg> entry : byKey.entrySet()) {
                    Map<KeyId, Long> relaxed = new LinkedHashMap<>(allowed);
                    relaxed.put(entry.getKey(), entry.getValue().requiredPieces());
                    long gain = jointMaximum(new ArrayList<>(lines.values()), byKey, relaxed) - totalMax;
                    if (gain > bestGain) {
                        bestGain = gain;
                        KeyAgg agg = entry.getValue();
                        bottleneck = agg.groupName != null ? agg.groupName : agg.operationName;
                        bottleneckId = agg.representativeOperationId;
                    }
                }
            }

            return new ProductProgress(
                    productId,
                    productName,
                    List.copyOf(lines.keySet()),
                    requiredProducts,
                    byKey.isEmpty() ? 0 : totalMin,
                    byKey.isEmpty() ? 0 : totalMax,
                    byKey.isEmpty() ? null : percent(totalMin, requiredProducts),
                    byKey.isEmpty() ? null : percent(totalMax, requiredProducts),
                    bottleneckId,
                    bottleneck,
                    operations,
                    lineRanges);
        }
    }

    // ── the exact searches (SPEC 5.6: never a greedy guess) ─────────────────

    /**
     * The MOST whole products any allowed allocation adds up to.
     *
     * <p>Depth-first over the lines, each taking every feasible count under
     * the remaining key capacities, the LAST line closed greedily (optimal
     * once all others are fixed). Pruned by the optimistic bound of the
     * remaining lines. Past {@link #MAX_SEARCH_NODES} it degrades SOFTLY to
     * that same optimistic bound — still an upper bound, merely looser.
     */
    private static long jointMaximum(
            List<LineInfo> lineList, Map<KeyId, KeyAgg> byKey, Map<KeyId, Long> allowed) {
        List<LineInfo> lines = lineList.stream()
                .filter(l -> byKey.values().stream().anyMatch(k -> k.perLine.containsKey(l.lineItemId)))
                .toList();
        if (lines.isEmpty()) {
            return 0;
        }
        Map<KeyId, Long> caps = new LinkedHashMap<>(allowed);
        long[] budget = {MAX_SEARCH_NODES};
        return searchMax(lines, 0, byKey, caps, budget);
    }

    private static long searchMax(List<LineInfo> lines, int index, Map<KeyId, KeyAgg> byKey,
                                  Map<KeyId, Long> caps, long[] budget) {
        LineInfo line = lines.get(index);
        long ub = lineCeiling(line, byKey, caps);
        if (index == lines.size() - 1) {
            return ub;
        }
        long best = 0;
        for (long f = ub; f >= 0; f--) {
            if (--budget[0] <= 0) {
                // Parachute: optimistic bound for everything left, still an
                // upper bound of the truth, just not the tight one.
                long optimistic = f;
                for (int j = index + 1; j < lines.size(); j++) {
                    optimistic += lineCeiling(lines.get(j), byKey, caps);
                }
                return Math.max(best, optimistic);
            }
            // Optimistic bound prune: nothing below can beat what we have.
            long optimisticRest = f;
            for (int j = index + 1; j < lines.size(); j++) {
                optimisticRest += lineCeiling(lines.get(j), byKey, caps);
            }
            if (optimisticRest <= best) {
                break;
            }
            Map<KeyId, Long> next = spend(line, f, byKey, caps);
            long total = f + searchMax(lines, index + 1, byKey, next, budget);
            if (total > best) {
                best = total;
            }
        }
        return best;
    }

    private static long lineCeiling(LineInfo line, Map<KeyId, KeyAgg> byKey, Map<KeyId, Long> caps) {
        long ceiling = line.quantity;
        boolean hasKey = false;
        for (Map.Entry<KeyId, KeyAgg> entry : byKey.entrySet()) {
            LineNeed need = entry.getValue().perLine.get(line.lineItemId);
            if (need == null || need.units <= 0) {
                continue;
            }
            hasKey = true;
            ceiling = Math.min(ceiling, Math.min(caps.get(entry.getKey()), need.needed) / need.units);
        }
        return hasKey ? Math.max(0, ceiling) : 0;
    }

    private static Map<KeyId, Long> spend(LineInfo line, long pieces,
                                          Map<KeyId, KeyAgg> byKey, Map<KeyId, Long> caps) {
        Map<KeyId, Long> next = new LinkedHashMap<>(caps);
        for (Map.Entry<KeyId, KeyAgg> entry : byKey.entrySet()) {
            LineNeed need = entry.getValue().perLine.get(line.lineItemId);
            if (need == null || need.units <= 0) {
                continue;
            }
            next.merge(entry.getKey(), -pieces * need.units, Long::sum);
        }
        return next;
    }

    /**
     * The FEWEST whole products consistent with what was recorded.
     *
     * <p>Exact by enumeration over "which key starves each line": for a fixed
     * choice the worst allocation has a closed form — every other line of that
     * key absorbs its full need first, the leftover lands on the starved lines
     * split to waste the most (each can swallow units−1 pieces without
     * completing anything). The enumeration is the whole space of worst cases;
     * the minimum over it is the true floor (proof sketched beside the SPEC's
     * worked example in the test). Past {@link #MAX_SEARCH_NODES} combinations
     * it degrades SOFTLY to Σ of the per-line minima — still a valid floor.
     */
    private static long jointMinimum(
            List<LineInfo> lineList, Map<KeyId, KeyAgg> byKey, Map<KeyId, Long> allowed) {
        List<LineInfo> lines = lineList.stream()
                .filter(l -> byKey.values().stream().anyMatch(k -> k.perLine.containsKey(l.lineItemId)))
                .toList();
        if (lines.isEmpty()) {
            return 0;
        }
        List<List<KeyId>> choices = new ArrayList<>();
        long combinations = 1;
        for (LineInfo line : lines) {
            List<KeyId> keysOfLine = new ArrayList<>();
            for (Map.Entry<KeyId, KeyAgg> entry : byKey.entrySet()) {
                if (entry.getValue().perLine.containsKey(line.lineItemId)) {
                    keysOfLine.add(entry.getKey());
                }
            }
            choices.add(keysOfLine);
            combinations *= Math.max(1, keysOfLine.size());
            if (combinations > MAX_SEARCH_NODES) {
                // Parachute: per-line floors, individually achievable, so their
                // sum cannot exceed the truth — a valid, looser floor.
                long sum = 0;
                for (LineInfo l : lines) {
                    long lineMin = l.quantity;
                    for (Map.Entry<KeyId, KeyAgg> entry : byKey.entrySet()) {
                        LineNeed need = entry.getValue().perLine.get(l.lineItemId);
                        if (need == null || need.units <= 0) continue;
                        long others = entry.getValue().requiredPieces() - need.needed;
                        lineMin = Math.min(lineMin,
                                Math.max(0, allowed.get(entry.getKey()) - others) / need.units);
                    }
                    sum += lineMin;
                }
                return sum;
            }
        }

        long best = Long.MAX_VALUE;
        int[] pick = new int[lines.size()];
        while (true) {
            Map<KeyId, List<LineInfo>> starvedByKey = new LinkedHashMap<>();
            for (int i = 0; i < lines.size(); i++) {
                starvedByKey.computeIfAbsent(choices.get(i).get(pick[i]), k -> new ArrayList<>())
                        .add(lines.get(i));
            }
            long total = 0;
            for (Map.Entry<KeyId, List<LineInfo>> entry : starvedByKey.entrySet()) {
                total += starvedFloors(entry.getKey(), entry.getValue(), byKey, allowed);
            }
            best = Math.min(best, total);

            int i = 0;
            while (i < pick.length && ++pick[i] >= choices.get(i).size()) {
                pick[i] = 0;
                i++;
            }
            if (i == pick.length) {
                break;
            }
        }
        return best == Long.MAX_VALUE ? 0 : best;
    }

    /** The fewest whole pieces the starved lines of one key can be left with. */
    private static long starvedFloors(KeyId keyId, List<LineInfo> starved,
                                      Map<KeyId, KeyAgg> byKey, Map<KeyId, Long> allowed) {
        KeyAgg agg = byKey.get(keyId);
        long othersNeed = 0;
        for (Map.Entry<Long, LineNeed> entry : agg.perLine.entrySet()) {
            boolean isStarved = starved.stream().anyMatch(l -> l.lineItemId.equals(entry.getKey()));
            if (!isStarved) {
                othersNeed += entry.getValue().needed;
            }
        }
        long leftover = Math.max(0, allowed.get(keyId) - othersNeed);
        if (leftover == 0) {
            return 0;
        }
        // Everybody first swallows units−1 pieces — the most that completes
        // nothing. What remains must complete pieces; fewest when it lands on
        // the lines with the LARGEST units first.
        List<LineNeed> needs = new ArrayList<>();
        for (LineInfo line : starved) {
            needs.add(agg.perLine.get(line.lineItemId));
        }
        long waste = 0;
        for (LineNeed need : needs) {
            waste += Math.min(need.units - 1, need.needed);
        }
        long remaining = leftover - waste;
        if (remaining <= 0) {
            return 0;
        }
        needs.sort(Comparator.comparingLong((LineNeed n) -> n.units).reversed());
        long floors = 0;
        for (LineNeed need : needs) {
            if (remaining <= 0) {
                break;
            }
            long capacity = need.needed - Math.min(need.units - 1, need.needed);
            long take = Math.min(remaining, capacity);
            floors += (take + need.units - 1) / need.units; // ceil
            remaining -= take;
        }
        return floors;
    }

    private static final class LineInfo {
        private final Long lineItemId;
        private final long quantity;
        private final Long variantId;
        private final String variantLabel;

        private LineInfo(Long lineItemId, long quantity, Long variantId, String variantLabel) {
            this.lineItemId = lineItemId;
            this.quantity = quantity;
            this.variantId = variantId;
            this.variantLabel = variantLabel;
        }
    }

    private static final class KeyAgg {
        private final Long representativeOperationId;
        private final String groupName;
        private final Long groupId;
        private final String operationName;
        private final Map<Long, LineNeed> perLine = new LinkedHashMap<>();

        private KeyAgg(Long representativeOperationId, String groupName, Long groupId, String operationName) {
            this.representativeOperationId = representativeOperationId;
            this.groupName = groupName;
            this.groupId = groupId;
            this.operationName = operationName;
        }

        private long requiredPieces() {
            return perLine.values().stream().mapToLong(n -> n.needed).sum();
        }
    }

    private record LineNeed(long needed, long units) {
        /** Two razrada rows of one key for one line: the larger statement wins. */
        private static LineNeed mergeMax(LineNeed a, LineNeed b) {
            return a.needed >= b.needed ? a : b;
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
        return value == null ? 0L : Objects.requireNonNull(value).longValue();
    }
}
