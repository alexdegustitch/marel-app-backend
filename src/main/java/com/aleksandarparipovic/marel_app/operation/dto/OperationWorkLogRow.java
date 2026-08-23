package com.aleksandarparipovic.marel_app.operation.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One recorded piece of work on this operation: who, how much, and when —
 * the work DATE plus the span within it, because "48 komada" means something
 * different in two hours than in a whole shift.
 */
public record OperationWorkLogRow(
        Long workLogId,
        /** Who did it — id so the row can open the employee. */
        Long employeeId,
        String employeeName,
        LocalDate workDate,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        Integer durationMin,
        Integer quantity,
        Integer scrap,
        /** The order this work was logged against — id so the row can open it. */
        Long orderId,
        String orderCode
) {
}
