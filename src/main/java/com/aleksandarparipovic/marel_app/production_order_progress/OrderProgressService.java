package com.aleksandarparipovic.marel_app.production_order_progress;

import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationOutputRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationRef;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationRefRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationRequirementRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgressSummary;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ScopeRequirementRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.UnscopedLineCountRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much of a production order is done.
 *
 * <p>Computed when it is asked for, from the work logs themselves, rather than
 * kept as a counter somebody has to remember to update. A work log can be
 * created, have its quantity, its operation or its order changed, be withdrawn,
 * be restored, and be deleted outright by the yearly purge — which runs raw SQL
 * and never passes through a service. A counter would have to be right on every
 * one of those paths, and a wrong one could never be noticed, because there
 * would be nothing left to compare it against. A sum cannot be stale.
 *
 * <p>What makes that affordable is the index, not the arithmetic: see
 * {@link OrderProgressQueryRepository} and migration V33 for the measurements.
 * If the day ever comes when the sum is too slow, everything that reads it goes
 * through {@link #forOrders(Collection)}, so the source can be replaced without
 * touching a caller.
 */
@Service
@RequiredArgsConstructor
public class OrderProgressService {

    private final OrderProgressQueryRepository repository;

    /** One order, fully broken down. Never null: an unknown order has no scope. */
    @Transactional(readOnly = true)
    public OrderProgress forOrder(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        return forOrders(List.of(orderId)).get(orderId);
    }

    /**
     * Several orders at once — what a list of order cards needs.
     *
     * <p>Four queries whatever the number of orders, never one per order.
     */
    @Transactional(readOnly = true)
    public Map<Long, OrderProgress> forOrders(Collection<Long> orderIds) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (Long id : orderIds) {
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<ScopeRequirementRow> requirements = repository.findRequirements(ids);
        List<OperationOutputRow> output = repository.findOutput(ids);

        Map<Long, Integer> linesWithoutScope = new HashMap<>();
        for (UnscopedLineCountRow row : repository.countLinesWithoutScope(ids)) {
            linesWithoutScope.put(row.getOrderId(), row.getLineCount() == null ? 0 : row.getLineCount().intValue());
        }

        return OrderProgressCalculator.calculate(
                requirements, output, strayOperations(requirements, output), linesWithoutScope, ids);
    }

    /** The compact figure a card or a table row shows. */
    @Transactional(readOnly = true)
    public Map<Long, OrderProgressSummary> summaries(Collection<Long> orderIds) {
        Map<Long, OrderProgressSummary> summaries = new LinkedHashMap<>();
        forOrders(orderIds).forEach((id, progress) -> summaries.put(id, OrderProgressSummary.of(progress)));
        return summaries;
    }

    /**
     * What every order's agreed scope asks of one operation.
     *
     * <p>For the operation detail page, which asks the question the other way
     * round: it holds an operation and wants each order's requirement for it.
     * Orders with no agreed scope are simply absent, so the caller can fall back
     * to the catalogue and say which of the two it used.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> agreedRequirementForOperation(Long operationId) {
        if (operationId == null) {
            return Map.of();
        }
        Map<Long, Long> required = new LinkedHashMap<>();
        for (OperationRequirementRow row : repository.findRequiredPiecesForOperation(operationId)) {
            if (row.getRequiredPieces() != null) {
                required.put(row.getOrderId(), row.getRequiredPieces());
            }
        }
        return required;
    }

    /**
     * Names for the operations that were worked but are not in any of these
     * orders' scopes.
     *
     * <p>Looked up only for the strays rather than for everything worked: the
     * ones inside the scope already carry their agreed name, which is the
     * snapshot the order settled on and not whatever the catalogue says today.
     */
    private Map<Long, OperationRef> strayOperations(
            List<ScopeRequirementRow> requirements, List<OperationOutputRow> output) {

        Set<Long> inScope = new HashSet<>();
        for (ScopeRequirementRow row : requirements) {
            inScope.add(row.getOperationId());
        }
        Set<Long> strays = new HashSet<>();
        for (OperationOutputRow row : output) {
            if (row.getOperationId() != null
                    && !inScope.contains(row.getOperationId())
                    && row.getDonePieces() != null
                    && row.getDonePieces() > 0) {
                strays.add(row.getOperationId());
            }
        }
        if (strays.isEmpty()) {
            return Map.of();
        }

        Map<Long, OperationRef> refs = new HashMap<>();
        for (OperationRefRow row : repository.findOperationRefs(strays)) {
            refs.put(row.getOperationId(), new OperationRef(
                    row.getOperationId(), row.getOperationName(), row.getProductId(), row.getProductName()));
        }
        return refs;
    }
}
