package com.aleksandarparipovic.marel_app.work_log.interval;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * The single interval engine for a shift: global covered time, PL/PLB partitioning
 * and verified (coefficient-weighted) time all come from here.
 *
 * <p>There is deliberately ONE partition algorithm. Duration and verified time are
 * two readings of the same segmentation, so the summary figure can never disagree
 * with the timeline it is derived from.
 *
 * <h2>Covered time</h2>
 * The union of all valid intervals across the whole shift, regardless of work-code
 * category. Overlapping wall-clock time is counted once and gaps are not counted:
 * 06:00–09:00 with 08:00–10:00 is 240 minutes, and 06:00–08:00 with 08:30–10:00 is
 * 210. Categories are never merged separately and per-category totals are never
 * summed — that is what used to double-count cross-category overlaps.
 *
 * <h2>PL/PLB</h2>
 * Only parallel-capable logs raise concurrency. A segment is PLB while three or
 * more of them are simultaneously active, and only for that exact segment; every
 * other covered segment with a parallel-capable log active is PL. Ordinary work
 * stays ORDINARY and never becomes PL or PLB.
 *
 * <h2>Conflict</h2>
 * Separate from PLB. Concurrency among parallel-capable logs is permitted however
 * deep it goes; a conflict is an ordinary log overlapping anything else.
 *
 * <h2>Arithmetic</h2>
 * Lengths are accumulated in seconds so that summing segments cannot drift from the
 * covered total. Coefficients are applied per segment as exact {@link BigDecimal}
 * products; rounding happens once, at the end, to {@link #VERIFIED_MINUTES_SCALE}
 * decimal places — never per interval and never per row.
 */
@Component
public class WorkIntervalCalculator {

    /** A segment is PLB while at least this many parallel-capable logs are active. */
    public static final int PLB_MIN_CONCURRENCY = 3;

    /** Verified minutes are rounded once, at the end, to this scale. */
    public static final int VERIFIED_MINUTES_SCALE = 4;

    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);

    /** Valid intervals only, in deterministic order. */
    public List<WorkIntervalInput> usableIntervals(Collection<WorkIntervalInput> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return List.of();
        }
        return intervals.stream()
                .filter(WorkIntervalInput::isUsable)
                .sorted(WorkIntervalInput.DETERMINISTIC_ORDER)
                .toList();
    }

    /**
     * The union of all valid intervals, as {@code [start, end)} ranges.
     *
     * <p>Directly contiguous intervals are merged: a log ending exactly when the
     * next begins is uninterrupted work, which is the convention the shift summary
     * has always used.
     */
    public List<Range> mergeIntervals(Collection<WorkIntervalInput> intervals) {
        List<WorkIntervalInput> sorted = usableIntervals(intervals);
        List<Range> merged = new ArrayList<>();

        for (WorkIntervalInput interval : sorted) {
            Range last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && !interval.startAt().isAfter(last.end())) {
                if (interval.endAt().isAfter(last.end())) {
                    merged.set(merged.size() - 1, new Range(last.start(), interval.endAt()));
                }
            } else {
                merged.add(new Range(interval.startAt(), interval.endAt()));
            }
        }
        return merged;
    }

    /** Total covered seconds — the union, so overlaps count once and gaps not at all. */
    public long coveredSeconds(Collection<WorkIntervalInput> intervals) {
        long total = 0;
        for (Range range : mergeIntervals(intervals)) {
            total += range.seconds();
        }
        return total;
    }

    /** Total covered whole minutes. This is the authoritative shift duration. */
    public long coveredMinutes(Collection<WorkIntervalInput> intervals) {
        return coveredSeconds(intervals) / 60L;
    }

    /**
     * Split the shift into non-overlapping segments with a constant active set.
     *
     * <p>Every distinct boundary becomes a cut point; a log is active over a segment
     * only if it covers the whole segment, which is what makes equal boundaries and
     * "one log ends as another starts" behave correctly. Uncovered gaps produce no
     * segment at all, and adjacent segments sharing an identical active set are
     * merged so a 2 → 3 → 2 concurrency change yields exactly three segments.
     *
     * @param plbCoefficient coefficient for PLB segments; when null, a PLB segment
     *                       falls back to the same rule as PL rather than silently
     *                       weighting at zero
     */
    public List<TimelineSegment> partition(Collection<WorkIntervalInput> intervals, BigDecimal plbCoefficient) {
        List<WorkIntervalInput> usable = usableIntervals(intervals);
        if (usable.isEmpty()) {
            return List.of();
        }

        TreeSet<OffsetDateTime> boundaries = new TreeSet<>();
        for (WorkIntervalInput interval : usable) {
            boundaries.add(interval.startAt());
            boundaries.add(interval.endAt());
        }

        List<OffsetDateTime> cuts = new ArrayList<>(boundaries);
        List<TimelineSegment> segments = new ArrayList<>();

        for (int i = 0; i < cuts.size() - 1; i++) {
            OffsetDateTime start = cuts.get(i);
            OffsetDateTime end = cuts.get(i + 1);

            List<WorkIntervalInput> active = usable.stream()
                    .filter(in -> !in.startAt().isAfter(start) && !in.endAt().isBefore(end))
                    .toList();
            if (active.isEmpty()) {
                continue; // gap
            }

            segments.add(buildSegment(start, end, active, plbCoefficient));
        }

        return mergeAdjacentEqualSegments(segments);
    }

    /**
     * Verified minutes for a shift.
     *
     * <p>{@code Σ(segment duration × that segment's coefficient)} over the
     * non-overlapping partition. Two rows running at the same moment are therefore
     * paid for that moment once, and gaps contribute nothing.
     */
    public VerifiedTime computeVerifiedTime(Collection<WorkIntervalInput> intervals, BigDecimal plbCoefficient) {
        List<TimelineSegment> segments = partition(intervals, plbCoefficient);
        if (segments.isEmpty()) {
            return VerifiedTime.empty();
        }

        long plSeconds = 0;
        long plbSeconds = 0;
        long ordinarySeconds = 0;
        BigDecimal weightedSeconds = BigDecimal.ZERO;

        for (TimelineSegment segment : segments) {
            switch (segment.coefficientType()) {
                case PLB -> plbSeconds += segment.durationSeconds();
                case PL -> plSeconds += segment.durationSeconds();
                case ORDINARY -> ordinarySeconds += segment.durationSeconds();
            }
            weightedSeconds = weightedSeconds.add(
                    BigDecimal.valueOf(segment.durationSeconds()).multiply(segment.coefficient()));
        }

        BigDecimal verifiedMinutes = weightedSeconds
                .divide(SECONDS_PER_MINUTE, VERIFIED_MINUTES_SCALE, RoundingMode.HALF_UP);

        return new VerifiedTime(
                (plSeconds + plbSeconds + ordinarySeconds) / 60L,
                plSeconds / 60L,
                plbSeconds / 60L,
                ordinarySeconds / 60L,
                verifiedMinutes);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private TimelineSegment buildSegment(OffsetDateTime start,
                                         OffsetDateTime end,
                                         List<WorkIntervalInput> active,
                                         BigDecimal plbCoefficient) {
        int parallelCapableCount = (int) active.stream().filter(WorkIntervalInput::allowsParallelWork).count();
        int ordinaryCount = active.size() - parallelCapableCount;

        CoefficientType type;
        if (parallelCapableCount >= PLB_MIN_CONCURRENCY) {
            type = CoefficientType.PLB;
        } else if (parallelCapableCount >= 1) {
            type = CoefficientType.PL;
        } else {
            type = CoefficientType.ORDINARY;
        }

        // Overlap among parallel-capable logs is permitted at any depth — that is
        // what the capability means. A conflict is an ordinary log sharing time with
        // anything else, ordinary or not.
        boolean conflict = ordinaryCount >= 1 && active.size() >= 2;

        BigDecimal coefficient = (type == CoefficientType.PLB && plbCoefficient != null)
                ? plbCoefficient
                : highestCoefficient(active);

        List<Long> activeIds = active.stream()
                .map(WorkIntervalInput::workLogId)
                .sorted(java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .toList();

        return new TimelineSegment(
                start,
                end,
                Duration.between(start, end).getSeconds(),
                activeIds,
                parallelCapableCount,
                ordinaryCount,
                type,
                conflict,
                coefficient);
    }

    /**
     * When several categories are active over one segment their coefficients may
     * differ; the segment takes the highest. Deterministic, independent of input
     * order, and never pays a covered minute less than any single contributing
     * category would on its own.
     */
    private BigDecimal highestCoefficient(List<WorkIntervalInput> active) {
        return active.stream()
                .map(WorkIntervalInput::coefficientOrNeutral)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ONE);
    }

    private List<TimelineSegment> mergeAdjacentEqualSegments(List<TimelineSegment> segments) {
        List<TimelineSegment> merged = new ArrayList<>();
        for (TimelineSegment segment : segments) {
            TimelineSegment last = merged.isEmpty() ? null : merged.getLast();
            if (last != null
                    && last.end().isEqual(segment.start())
                    && last.activeWorkLogIds().equals(segment.activeWorkLogIds())) {
                merged.set(merged.size() - 1, new TimelineSegment(
                        last.start(),
                        segment.end(),
                        last.durationSeconds() + segment.durationSeconds(),
                        last.activeWorkLogIds(),
                        last.parallelCapableCount(),
                        last.ordinaryCount(),
                        last.coefficientType(),
                        last.conflict(),
                        last.coefficient()));
            } else {
                merged.add(segment);
            }
        }
        return merged;
    }

    /** A merged {@code [start, end)} range of covered time. */
    public record Range(OffsetDateTime start, OffsetDateTime end) {
        public long seconds() {
            return Duration.between(start, end).getSeconds();
        }

        public long minutes() {
            return seconds() / 60L;
        }
    }

    /** Covered-time breakdown and the coefficient-weighted result. */
    public record VerifiedTime(
            long coveredMinutes,
            long plMinutes,
            long plbMinutes,
            long ordinaryMinutes,
            BigDecimal verifiedMinutes
    ) {
        public static VerifiedTime empty() {
            return new VerifiedTime(0, 0, 0, 0, BigDecimal.ZERO.setScale(VERIFIED_MINUTES_SCALE));
        }
    }
}
