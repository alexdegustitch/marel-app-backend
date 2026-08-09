package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
        /** How many lines the snapshot holds. */
        int lineCount,
        /**
         * Whether a full snapshot of the payroll was captured. False for
         * handovers recorded before snapshots existed — those can still say who
         * and when, just not what the screen looked like.
         */
        boolean hasSnapshot,
        String note
) {
}
