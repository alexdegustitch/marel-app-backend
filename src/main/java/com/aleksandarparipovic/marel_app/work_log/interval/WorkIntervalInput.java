package com.aleksandarparipovic.marel_app.work_log.interval;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;

/**
 * One work-log interval as the interval engine sees it — the minimal shape needed
 * to compute covered time and PL/PLB classification, decoupled from the JPA entity
 * so the algorithm stays unit-testable without a database.
 *
 * <p>{@code startAt}/{@code endAt} are absolute instants, which is what makes
 * overnight shifts work with no special handling: a 22:00→06:00 log is simply an
 * eight-hour range on the timeline, exactly as the rest of the backend stores it.
 *
 * <p>{@code allowsParallelWork} is the authoritative database-backed capability
 * ({@code work_code_categories.allows_parallel_work}). Category names and the
 * literal strings "PL"/"PLB" are never consulted.
 *
 * <p>{@code normMultiplier} is the category's own coefficient
 * ({@code work_code_categories.norm_multiplier}).
 */
public record WorkIntervalInput(
        Long workLogId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        boolean allowsParallelWork,
        BigDecimal normMultiplier
) {

    /**
     * Sort by start, then end, then id. Stable identity last so that intervals
     * sharing both boundaries still order deterministically, which is what makes
     * the engine's output independent of the order rows arrive from the database.
     */
    public static final Comparator<WorkIntervalInput> DETERMINISTIC_ORDER =
            Comparator.comparing(WorkIntervalInput::startAt)
                    .thenComparing(WorkIntervalInput::endAt)
                    .thenComparing(i -> i.workLogId() == null ? Long.MIN_VALUE : i.workLogId());

    /**
     * Incomplete (null boundary), zero-length and negative intervals are excluded
     * here rather than rejected: the database already forbids them
     * ({@code start_at}/{@code end_at} are NOT NULL and
     * {@code chk_work_logs_duration_min} requires at least one minute), so anything
     * reaching this point is defensive and must not corrupt a shift's totals.
     */
    public boolean isUsable() {
        return startAt != null && endAt != null && startAt.isBefore(endAt);
    }

    /** The category coefficient, defaulting to a neutral 1 when unset. */
    public BigDecimal coefficientOrNeutral() {
        return normMultiplier == null ? BigDecimal.ONE : normMultiplier;
    }
}
