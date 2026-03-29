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
}
