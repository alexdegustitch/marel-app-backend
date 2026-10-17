package com.aleksandarparipovic.marel_app.work_log.validation;

import com.aleksandarparipovic.marel_app.operation.Operation;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class WorkLogValidator {
    public void validateTimeRange(OffsetDateTime startAt, OffsetDateTime endAt) {
        if (endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("endAt must be after or equal to startAt");
        }
    }

    public void validateOperationProductConsistency(Long productId, Operation operation) {
        if (productId == null) {
            return;
        }
        if (operation == null || operation.getProduct() == null || !productId.equals(operation.getProduct().getId())) {
            throw new IllegalArgumentException("productId does not match selected operation");
        }
    }

    /**
     * A standalone (osnovna) operation belongs to no product and no order, and
     * nothing is produced by it — a log carrying one must not claim otherwise.
     * Quantities are refused rather than silently zeroed: a number somebody
     * typed is a statement, and a statement that cannot be true is an error.
     */
    public void validateStandaloneConsistency(Operation operation, Long productionOrderId,
                                              Integer quantity, Integer scrap) {
        if (operation == null || !operation.isStandalone()) {
            return;
        }
        if (productionOrderId != null) {
            throw new IllegalArgumentException("Osnovna operacija se ne vezuje za nalog");
        }
        if ((quantity != null && quantity != 0) || (scrap != null && scrap != 0)) {
            throw new IllegalArgumentException("Osnovna operacija nema količinu ni škart");
        }
    }
}
