package com.aleksandarparipovic.marel_app.dashboard.insight.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The row shapes stored inside {@code dashboard_insights.payload}.
 *
 * <p>Held together in one file because they are one thing: the vocabulary of the
 * daily snapshot. They are written by {@code DashboardInsightComputeService} and
 * read straight back onto the wire, so a field renamed here changes both the
 * stored payload and the API — which is why every one of them carries the pieces
 * its headline figure was computed from. A card that says "138 %" without saying
 * "on 4 200 pieces over 63 hours" invites a decision nobody can check.
 */
public final class InsightRows {

    private InsightRows() {
    }

    /**
     * An operation measured against the norm currently in force.
     *
     * @param ratePct  pieces per hour over the window, as a percentage of
     *                 {@code min_norm}. UNCAPPED on purpose: the paid rate is
     *                 limited by {@code max_efficiency_percent}, and a ceiling is
     *                 exactly what would hide a norm set too low.
     * @param minNorm  the norm this was measured against — the current one, since
     *                 the question is whether TODAY's norm still fits.
     */
    public record NormFitRow(
            Long operationId,
            String operationName,
            Long productId,
            String productName,
            Integer minNorm,
            Integer maxNorm,
            BigDecimal ratePct,
            Long quantity,
            Long durationMin,
            Integer employeeCount,
            Integer logCount
    ) {}

    /** An operation worked without a norm. */
    public record NoNormRow(
            Long operationId,
            String operationName,
            Long productId,
            String productName,
            Long quantity,
            Long durationMin,
            Integer employeeCount,
            LocalDate lastWorkedOn
    ) {}

    /** How much an operation was worked, for the most/least and yesterday cards. */
    public record OperationVolumeRow(
            Long operationId,
            String operationName,
            Long productId,
            String productName,
            Long quantity,
            Long durationMin,
            Integer employeeCount
    ) {}

    /** How much a product was worked. */
    public record ProductVolumeRow(
            Long productId,
            String productName,
            Long quantity,
            Long durationMin,
            Integer operationCount,
            Integer employeeCount
    ) {}

    /**
     * An employee's sustained performance.
     *
     * @param ratePct         time-weighted mean of the UNCAPPED per-log rate — what
     *                        was actually produced. Ranking is by this.
     * @param approvedRatePct the same figure after the efficiency ceiling and the
     *                        probation substitution, i.e. what payroll recognises.
     *                        Shown beside it so the two are never confused.
     */
    public record PerformerRow(
            Long employeeId,
            String employeeName,
            String employeeNo,
            BigDecimal ratePct,
            BigDecimal approvedRatePct,
            Long durationMin,
            Integer dayCount,
            Integer operationCount
    ) {}

    /** A shift holding neither work nor an absence. */
    public record MissingEntryRow(
            Long workShiftId,
            Long employeeId,
            String employeeName,
            LocalDate workDate,
            String shiftCode,
            Integer shiftMinutes
    ) {}

    /**
     * How far apart employees are on one operation.
     *
     * @param spreadPct the gap in percentage points between the best and the worst
     *                  sustained rate on this operation.
     */
    public record SpreadRow(
            Long operationId,
            String operationName,
            Long productId,
            String productName,
            BigDecimal lowestPct,
            BigDecimal highestPct,
            BigDecimal spreadPct,
            String lowestEmployeeName,
            String highestEmployeeName,
            Integer employeeCount,
            Long durationMin
    ) {}

    /**
     * An operation scrapping more than it used to.
     *
     * @param baselinePct the same operation's scrap share over the longer period
     *                    BEFORE the window — the operation is compared with itself,
     *                    never with a factory-wide average that means nothing for a
     *                    difficult part.
     */
    public record ScrapRow(
            Long operationId,
            String operationName,
            Long productId,
            String productName,
            Long scrap,
            Long quantity,
            BigDecimal scrapPct,
            BigDecimal baselinePct,
            BigDecimal deltaPp
    ) {}
}
