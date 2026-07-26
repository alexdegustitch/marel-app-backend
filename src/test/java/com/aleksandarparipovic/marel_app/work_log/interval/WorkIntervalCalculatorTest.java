package com.aleksandarparipovic.marel_app.work_log.interval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interval engine is pure arithmetic over time ranges, so it is tested without
 * Spring or a database. The owner's worked examples appear verbatim.
 */
class WorkIntervalCalculatorTest {

    private final WorkIntervalCalculator calculator = new WorkIntervalCalculator();

    private static final LocalDate DAY = LocalDate.of(2026, 7, 20);
    private static final BigDecimal PL_RATE = new BigDecimal("1.00");
    private static final BigDecimal PLB_RATE = new BigDecimal("1.50");

    private static OffsetDateTime at(String hhmm) {
        return OffsetDateTime.of(DAY, LocalTime.parse(hhmm), ZoneOffset.UTC);
    }

    /** A parallel-capable interval. */
    private static WorkIntervalInput parallel(long id, String start, String end) {
        return new WorkIntervalInput(id, at(start), at(end), true, PL_RATE);
    }

    /** An ordinary (non-parallel-capable) interval. */
    private static WorkIntervalInput ordinary(long id, String start, String end) {
        return new WorkIntervalInput(id, at(start), at(end), false, PL_RATE);
    }

    // =========================================================================
    // Global duration union
    // =========================================================================

    @Nested
    @DisplayName("Global duration union")
    class GlobalDurationUnion {

        @Test
        void singleInterval() {
            assertThat(calculator.coveredMinutes(List.of(parallel(1, "06:00", "09:00")))).isEqualTo(180);
        }

        @Test
        @DisplayName("owner example: 06:00-09:00 + 08:00-10:00 = 240")
        void ownerExampleOverlapping() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "09:00"),
                    parallel(2, "08:00", "10:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(240);
        }

        @Test
        @DisplayName("owner example: 06:00-08:00 + 08:30-10:00 = 210 (gap not counted)")
        void ownerExampleWithGap() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "08:00"),
                    parallel(2, "08:30", "10:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(210);
        }

        @Test
        @DisplayName("owner example: four overlapping intervals = 240")
        void ownerExampleFourOverlapping() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "09:00"),
                    parallel(2, "07:00", "08:00"),
                    parallel(3, "07:00", "09:30"),
                    parallel(4, "07:00", "10:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(240);
        }

        @Test
        void nestedIntervalAddsNothing() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "10:00"),
                    parallel(2, "07:00", "08:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(240);
        }

        @Test
        void directlyAdjacentIntervalsAreContinuousWork() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "08:00"),
                    parallel(2, "08:00", "10:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(240);
            assertThat(calculator.mergeIntervals(intervals)).hasSize(1);
        }

        @Test
        void disjointIntervalsWithGaps() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "07:00"),
                    parallel(2, "08:00", "09:00"),
                    parallel(3, "10:00", "11:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(180);
            assertThat(calculator.mergeIntervals(intervals)).hasSize(3);
        }

        @Test
        @DisplayName("union ignores category entirely — mixed categories still count once")
        void mixedCategoriesCountedOnce() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "09:00"),
                    ordinary(2, "08:00", "10:00"));
            // Per-category merging would give 180 + 120 = 300. The union is 240.
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(240);
        }

        @Test
        void mixedCoefficientsDoNotAffectCoverage() {
            List<WorkIntervalInput> intervals = List.of(
                    new WorkIntervalInput(1L, at("06:00"), at("09:00"), true, new BigDecimal("1.40")),
                    new WorkIntervalInput(2L, at("08:00"), at("10:00"), false, new BigDecimal("0.50")));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(240);
        }

        @Test
        void incompleteIntervalsAreIgnored() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "09:00"),
                    new WorkIntervalInput(2L, at("07:00"), null, true, PL_RATE),
                    new WorkIntervalInput(3L, null, at("11:00"), true, PL_RATE));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(180);
        }

        @Test
        void zeroLengthIntervalsAreIgnored() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "09:00"),
                    parallel(2, "10:00", "10:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(180);
        }

        @Test
        @DisplayName("negative interval is discarded, never subtracted")
        void negativeIntervalsAreIgnored() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "09:00"),
                    parallel(2, "11:00", "10:00"));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(180);
        }

        @Test
        @DisplayName("overnight interval spans midnight as one absolute range")
        void overnightInterval() {
            OffsetDateTime start = OffsetDateTime.of(DAY, LocalTime.of(22, 0), ZoneOffset.UTC);
            OffsetDateTime end = OffsetDateTime.of(DAY.plusDays(1), LocalTime.of(6, 0), ZoneOffset.UTC);
            WorkIntervalInput overnight = new WorkIntervalInput(1L, start, end, true, PL_RATE);
            assertThat(calculator.coveredMinutes(List.of(overnight))).isEqualTo(480);
        }

        @Test
        void overnightOverlapCountedOnce() {
            OffsetDateTime start = OffsetDateTime.of(DAY, LocalTime.of(22, 0), ZoneOffset.UTC);
            OffsetDateTime mid = OffsetDateTime.of(DAY.plusDays(1), LocalTime.of(2, 0), ZoneOffset.UTC);
            OffsetDateTime end = OffsetDateTime.of(DAY.plusDays(1), LocalTime.of(6, 0), ZoneOffset.UTC);
            List<WorkIntervalInput> intervals = List.of(
                    new WorkIntervalInput(1L, start, mid, true, PL_RATE),
                    new WorkIntervalInput(2L, start.plusHours(1), end, true, PL_RATE));
            assertThat(calculator.coveredMinutes(intervals)).isEqualTo(480);
        }

        @Test
        @DisplayName("result is independent of input ordering")
        void deterministicRegardlessOfOrder() {
            List<WorkIntervalInput> intervals = new ArrayList<>(List.of(
                    parallel(1, "06:00", "09:00"),
                    parallel(2, "07:00", "08:00"),
                    parallel(3, "07:00", "09:30"),
                    parallel(4, "07:00", "10:00")));

            long expected = calculator.coveredMinutes(intervals);
            for (int i = 0; i < 25; i++) {
                Collections.shuffle(intervals);
                assertThat(calculator.coveredMinutes(intervals)).isEqualTo(expected);
            }
            assertThat(expected).isEqualTo(240);
        }

        @Test
        void emptyAndNullInputsAreSafe() {
            assertThat(calculator.coveredMinutes(List.of())).isZero();
            assertThat(calculator.coveredMinutes(null)).isZero();
        }
    }

    // =========================================================================
    // PL/PLB interval partition
    // =========================================================================

    @Nested
    @DisplayName("PL/PLB interval partition")
    class Partition {

        @Test
        void oneParallelCapableOperationIsPl() {
            List<TimelineSegment> segments = calculator.partition(
                    List.of(parallel(1, "06:00", "09:00")), PLB_RATE);

            assertThat(segments).hasSize(1);
            assertThat(segments.getFirst().coefficientType()).isEqualTo(CoefficientType.PL);
            assertThat(segments.getFirst().durationMinutes()).isEqualTo(180);
        }

        @Test
        void twoSimultaneousParallelCapableOperationsStayPl() {
            List<TimelineSegment> segments = calculator.partition(
                    List.of(parallel(1, "06:00", "09:00"), parallel(2, "07:00", "10:00")), PLB_RATE);

            assertThat(segments).allMatch(s -> s.coefficientType() == CoefficientType.PL);
        }

        @Test
        @DisplayName("three simultaneous parallel-capable operations produce the exact PLB interval only")
        void threeSimultaneousProduceExactPlbInterval() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "10:00"),
                    parallel(2, "07:00", "10:00"),
                    parallel(3, "08:00", "09:00")), PLB_RATE);

            List<TimelineSegment> plb = segments.stream()
                    .filter(s -> s.coefficientType() == CoefficientType.PLB).toList();

            assertThat(plb).hasSize(1);
            assertThat(plb.getFirst().start()).isEqualTo(at("08:00"));
            assertThat(plb.getFirst().end()).isEqualTo(at("09:00"));
            assertThat(plb.getFirst().durationMinutes()).isEqualTo(60);

            // The remainder of every participating operation stays PL.
            long plMinutes = segments.stream()
                    .filter(s -> s.coefficientType() == CoefficientType.PL)
                    .mapToLong(TimelineSegment::durationMinutes).sum();
            assertThat(plMinutes).isEqualTo(180);
        }

        @Test
        @DisplayName("2 -> 3 -> 2 concurrency yields three segments, only the middle PLB")
        void concurrencyTransition() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "12:00"),
                    parallel(2, "06:00", "12:00"),
                    parallel(3, "08:00", "10:00")), PLB_RATE);

            assertThat(segments).hasSize(3);
            assertThat(segments.get(0).coefficientType()).isEqualTo(CoefficientType.PL);
            assertThat(segments.get(1).coefficientType()).isEqualTo(CoefficientType.PLB);
            assertThat(segments.get(2).coefficientType()).isEqualTo(CoefficientType.PL);
            assertThat(segments.get(1).durationMinutes()).isEqualTo(120);
        }

        @Test
        void fourSimultaneousOperationsAreStillPlb() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "10:00"),
                    parallel(2, "06:00", "10:00"),
                    parallel(3, "06:00", "10:00"),
                    parallel(4, "06:00", "10:00")), PLB_RATE);

            assertThat(segments).hasSize(1);
            assertThat(segments.getFirst().coefficientType()).isEqualTo(CoefficientType.PLB);
            assertThat(segments.getFirst().parallelCapableCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("equal start/end boundaries do not create phantom concurrency")
        void equalBoundaries() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "08:00"),
                    parallel(2, "08:00", "10:00"),
                    parallel(3, "08:00", "10:00")), PLB_RATE);

            assertThat(segments).allMatch(s -> s.coefficientType() == CoefficientType.PL);
            assertThat(segments).noneMatch(s -> s.activeCount() >= 3);
        }

        @Test
        void nestedOperationsClassifyByConcurrency() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "12:00"),
                    parallel(2, "07:00", "11:00"),
                    parallel(3, "08:00", "10:00")), PLB_RATE);

            assertThat(segments.stream()
                    .filter(s -> s.coefficientType() == CoefficientType.PLB)
                    .mapToLong(TimelineSegment::durationMinutes).sum()).isEqualTo(120);
        }

        @Test
        void multipleDisjointPlbIntervals() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "14:00"),
                    parallel(2, "06:00", "14:00"),
                    parallel(3, "07:00", "08:00"),
                    parallel(4, "11:00", "12:00")), PLB_RATE);

            List<TimelineSegment> plb = segments.stream()
                    .filter(s -> s.coefficientType() == CoefficientType.PLB).toList();

            assertThat(plb).hasSize(2);
            assertThat(plb.get(0).start()).isEqualTo(at("07:00"));
            assertThat(plb.get(1).start()).isEqualTo(at("11:00"));
        }

        @Test
        @DisplayName("an operation is never wholly PLB because one portion qualifies")
        void noWholeOperationPlbClassification() {
            WorkIntervalInput longRunner = parallel(1, "06:00", "14:00");
            List<TimelineSegment> segments = calculator.partition(List.of(
                    longRunner,
                    parallel(2, "08:00", "09:00"),
                    parallel(3, "08:00", "09:00")), PLB_RATE);

            long plbForLongRunner = segments.stream()
                    .filter(s -> s.coefficientType() == CoefficientType.PLB)
                    .filter(s -> s.activeWorkLogIds().contains(1L))
                    .mapToLong(TimelineSegment::durationMinutes).sum();

            assertThat(plbForLongRunner).isEqualTo(60);
            assertThat(calculator.coveredMinutes(List.of(longRunner))).isEqualTo(480);
        }

        @Test
        void singleOrdinaryOperationStaysOrdinary() {
            List<TimelineSegment> segments = calculator.partition(
                    List.of(ordinary(1, "06:00", "09:00")), PLB_RATE);

            assertThat(segments).hasSize(1);
            assertThat(segments.getFirst().coefficientType()).isEqualTo(CoefficientType.ORDINARY);
            assertThat(segments.getFirst().conflict()).isFalse();
        }

        @Test
        @DisplayName("three ordinary operations never reach PLB")
        void ordinaryOperationsNeverBecomePlb() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    ordinary(1, "06:00", "10:00"),
                    ordinary(2, "06:00", "10:00"),
                    ordinary(3, "06:00", "10:00")), PLB_RATE);

            assertThat(segments).noneMatch(s -> s.coefficientType() == CoefficientType.PLB);
            assertThat(segments.getFirst().coefficientType()).isEqualTo(CoefficientType.ORDINARY);
        }
    }

    // =========================================================================
    // Conflict
    // =========================================================================

    @Nested
    @DisplayName("Conflict classification")
    class Conflicts {

        @Test
        void parallelCapableOverlapIsNotAConflict() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "10:00"),
                    parallel(2, "07:00", "11:00")), PLB_RATE);

            assertThat(segments).noneMatch(TimelineSegment::conflict);
        }

        @Test
        @DisplayName("PLB concurrency is not a conflict")
        void plbIsNotAConflict() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "10:00"),
                    parallel(2, "06:00", "10:00"),
                    parallel(3, "06:00", "10:00")), PLB_RATE);

            assertThat(segments).allMatch(s -> s.coefficientType() == CoefficientType.PLB);
            assertThat(segments).noneMatch(TimelineSegment::conflict);
        }

        @Test
        void parallelCapableOverlappingOrdinaryIsAConflict() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "10:00"),
                    ordinary(2, "08:00", "12:00")), PLB_RATE);

            List<TimelineSegment> conflicting = segments.stream().filter(TimelineSegment::conflict).toList();
            assertThat(conflicting).hasSize(1);
            assertThat(conflicting.getFirst().start()).isEqualTo(at("08:00"));
            assertThat(conflicting.getFirst().end()).isEqualTo(at("10:00"));
        }

        @Test
        void ordinaryOverlappingOrdinaryIsAConflict() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    ordinary(1, "06:00", "10:00"),
                    ordinary(2, "08:00", "12:00")), PLB_RATE);

            assertThat(segments.stream().filter(TimelineSegment::conflict))
                    .singleElement()
                    .satisfies(s -> assertThat(s.durationMinutes()).isEqualTo(120));
        }

        @Test
        @DisplayName("conflict is interval-exact, not whole-operation")
        void conflictIsIntervalExact() {
            List<TimelineSegment> segments = calculator.partition(List.of(
                    parallel(1, "06:00", "14:00"),
                    ordinary(2, "08:00", "09:00")), PLB_RATE);

            long conflictMinutes = segments.stream()
                    .filter(TimelineSegment::conflict)
                    .mapToLong(TimelineSegment::durationMinutes).sum();
            assertThat(conflictMinutes).isEqualTo(60);
        }
    }

    // =========================================================================
    // Verified minutes
    // =========================================================================

    @Nested
    @DisplayName("Verified minutes")
    class Verified {

        @Test
        void plOnlyCoveredInterval() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(
                    List.of(parallel(1, "06:00", "09:00")), PLB_RATE);

            assertThat(result.coveredMinutes()).isEqualTo(180);
            assertThat(result.plMinutes()).isEqualTo(180);
            assertThat(result.plbMinutes()).isZero();
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("180.0000");
        }

        @Test
        void plbOnlyIntervalUsesThePlbCoefficient() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    parallel(1, "06:00", "08:00"),
                    parallel(2, "06:00", "08:00"),
                    parallel(3, "06:00", "08:00")), PLB_RATE);

            assertThat(result.plbMinutes()).isEqualTo(120);
            assertThat(result.plMinutes()).isZero();
            // 120 minutes at 1.50
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("180.0000");
        }

        @Test
        @DisplayName("PL -> PLB -> PL weights only the qualifying interval at the PLB rate")
        void plThenPlbThenPl() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    parallel(1, "06:00", "12:00"),
                    parallel(2, "06:00", "12:00"),
                    parallel(3, "08:00", "10:00")), PLB_RATE);

            assertThat(result.coveredMinutes()).isEqualTo(360);
            assertThat(result.plMinutes()).isEqualTo(240);
            assertThat(result.plbMinutes()).isEqualTo(120);
            // 240 × 1.00 + 120 × 1.50 = 420
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("420.0000");
        }

        @Test
        void gapsContributeZero() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    parallel(1, "06:00", "08:00"),
                    parallel(2, "10:00", "12:00")), PLB_RATE);

            assertThat(result.coveredMinutes()).isEqualTo(240);
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("240.0000");
        }

        @Test
        @DisplayName("overlapping rows are not double-counted")
        void overlappingRowsNotDoubleCounted() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    parallel(1, "06:00", "09:00"),
                    parallel(2, "08:00", "10:00")), PLB_RATE);

            assertThat(result.coveredMinutes()).isEqualTo(240);
            // Raw summing would give 300 minutes.
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("240.0000");
        }

        @Test
        void multiplePlbIntervalsAreEachWeighted() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    parallel(1, "06:00", "14:00"),
                    parallel(2, "06:00", "14:00"),
                    parallel(3, "07:00", "08:00"),
                    parallel(4, "11:00", "12:00")), PLB_RATE);

            assertThat(result.coveredMinutes()).isEqualTo(480);
            assertThat(result.plbMinutes()).isEqualTo(120);
            assertThat(result.plMinutes()).isEqualTo(360);
            // 360 × 1.00 + 120 × 1.50 = 540
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("540.0000");
        }

        @Test
        @DisplayName("pl + plb + ordinary always reconstructs covered minutes exactly")
        void breakdownReconstructsCoveredMinutes() {
            List<WorkIntervalInput> intervals = List.of(
                    parallel(1, "06:00", "12:00"),
                    parallel(2, "07:00", "11:00"),
                    parallel(3, "08:00", "10:00"),
                    ordinary(4, "13:00", "15:00"));

            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(intervals, PLB_RATE);

            assertThat(result.plMinutes() + result.plbMinutes() + result.ordinaryMinutes())
                    .isEqualTo(result.coveredMinutes())
                    .isEqualTo(calculator.coveredMinutes(intervals));
        }

        @Test
        @DisplayName("ordinary work is weighted by its own category coefficient, never the PL/PLB one")
        void ordinaryUsesItsOwnCoefficient() {
            WorkIntervalInput ordinaryHalfRate =
                    new WorkIntervalInput(1L, at("06:00"), at("08:00"), false, new BigDecimal("0.50"));

            WorkIntervalCalculator.VerifiedTime result =
                    calculator.computeVerifiedTime(List.of(ordinaryHalfRate), PLB_RATE);

            assertThat(result.ordinaryMinutes()).isEqualTo(120);
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("60.0000");
        }

        @Test
        @DisplayName("where coefficients differ the segment takes the highest")
        void highestCoefficientWinsOnASharedSegment() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    new WorkIntervalInput(1L, at("06:00"), at("08:00"), true, new BigDecimal("1.00")),
                    new WorkIntervalInput(2L, at("06:00"), at("08:00"), true, new BigDecimal("1.25"))),
                    PLB_RATE);

            assertThat(result.verifiedMinutes()).isEqualByComparingTo("150.0000");
        }

        @Test
        @DisplayName("a null PLB coefficient falls back to the PL rule instead of zeroing the interval")
        void nullPlbCoefficientFallsBack() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    parallel(1, "06:00", "08:00"),
                    parallel(2, "06:00", "08:00"),
                    parallel(3, "06:00", "08:00")), null);

            assertThat(result.plbMinutes()).isEqualTo(120);
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("120.0000");
        }

        @Test
        @DisplayName("decimal coefficients are exact and rounded once at the end")
        void decimalPrecision() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(
                    new WorkIntervalInput(1L, at("06:00"), at("06:07"), true, new BigDecimal("1.333"))),
                    PLB_RATE);

            // 7 × 1.333 = 9.331 exactly, no per-interval rounding drift
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("9.3310");
            assertThat(result.verifiedMinutes().scale()).isEqualTo(WorkIntervalCalculator.VERIFIED_MINUTES_SCALE);
        }

        @Test
        void emptyInputProducesZero() {
            WorkIntervalCalculator.VerifiedTime result = calculator.computeVerifiedTime(List.of(), PLB_RATE);
            assertThat(result.coveredMinutes()).isZero();
            assertThat(result.verifiedMinutes()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("verified minutes are independent of input ordering")
        void deterministicRegardlessOfOrder() {
            List<WorkIntervalInput> intervals = new ArrayList<>(List.of(
                    parallel(1, "06:00", "12:00"),
                    parallel(2, "07:00", "11:00"),
                    parallel(3, "08:00", "10:00"),
                    ordinary(4, "13:00", "15:00")));

            BigDecimal expected = calculator.computeVerifiedTime(intervals, PLB_RATE).verifiedMinutes();
            for (int i = 0; i < 25; i++) {
                Collections.shuffle(intervals);
                assertThat(calculator.computeVerifiedTime(intervals, PLB_RATE).verifiedMinutes())
                        .isEqualByComparingTo(expected);
            }
        }
    }
}
