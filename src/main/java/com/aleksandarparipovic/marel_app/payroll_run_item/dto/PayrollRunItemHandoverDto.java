package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One handover step as reported to the caller.
 *
 * <p>The amounts are null for anybody who may not see payroll figures — the
 * same rule as the live payroll, applied to the record of it. A stored
 * handover is not a way around the permission.
 */
public record PayrollRunItemHandoverDto(
        Long id,
        String event,
        Long actorId,
        String actorName,
        OffsetDateTime occurredAt,
        String statusBefore,
        String statusAfter,
        BigDecimal totalNetEarnings,
        BigDecimal netPayableAmount,
        /** The lines as handed over. Empty for a reader who may not see amounts. */
        Map<String, Object> payload,
        String note
) {
}
