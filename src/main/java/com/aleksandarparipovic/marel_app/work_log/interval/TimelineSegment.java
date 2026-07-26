package com.aleksandarparipovic.marel_app.work_log.interval;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One maximal stretch of wall-clock time over which the set of active work logs
 * does not change. Segments are non-overlapping and never zero-length, so summing
 * them counts every covered minute exactly once.
 *
 * @param start              segment start (inclusive)
 * @param end                segment end (exclusive)
 * @param durationSeconds    exact length; seconds rather than minutes so that
 *                           summing segments can never drift from the covered total
 * @param activeWorkLogIds   ids active over the whole segment, ascending
 * @param parallelCapableCount how many active logs allow parallel work
 * @param ordinaryCount      how many active logs do not
 * @param coefficientType    PL / PLB / ORDINARY for this exact segment
 * @param conflict           whether this segment violates the concurrency rules
 * @param coefficient        the coefficient applied to this segment's minutes
 */
public record TimelineSegment(
        OffsetDateTime start,
        OffsetDateTime end,
        long durationSeconds,
        List<Long> activeWorkLogIds,
        int parallelCapableCount,
        int ordinaryCount,
        CoefficientType coefficientType,
        boolean conflict,
        BigDecimal coefficient
) {

    /** Whole minutes covered by this segment. */
    public long durationMinutes() {
        return durationSeconds / 60L;
    }

    /** Total number of work logs active over this segment. */
    public int activeCount() {
        return activeWorkLogIds.size();
    }
}
