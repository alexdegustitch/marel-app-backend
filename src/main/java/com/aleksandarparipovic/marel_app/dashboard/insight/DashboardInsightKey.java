package com.aleksandarparipovic.marel_app.dashboard.insight;

/**
 * The analytical questions the daily job answers.
 *
 * <p>One key per card. The name is stored in {@code dashboard_insights.insight_key}
 * and is therefore part of the data, not just of the code: renaming one orphans
 * every row already written under the old name, so add rather than rename.
 *
 * <p>Each key's payload row shape lives in {@link com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows}.
 */
public enum DashboardInsightKey {

    /**
     * Operations whose recent output sits well ABOVE the norm in force — the
     * norm is easier than the work actually is.
     */
    NORM_TOO_LOW,

    /**
     * Operations whose recent output sits well BELOW the norm in force. Either
     * the norm is unreachable, or something about the work has changed.
     */
    NORM_TOO_HIGH,

    /**
     * Operations that carry no norm at all ({@code norm_required = false}) yet
     * produce a serious number of pieces. Every log on them is credited a flat
     * 100 %, so volume here is money nobody measured.
     */
    NO_NORM_HIGH_VOLUME,

    /** The operations the factory spent the most minutes on. */
    MOST_WORKED_OPERATIONS,

    /** The operations it spent the fewest minutes on, among those worked at all. */
    LEAST_WORKED_OPERATIONS,

    /** Yesterday's operations, by pieces made. */
    YESTERDAY_TOP_OPERATIONS,

    /** Yesterday's products, by pieces made. */
    YESTERDAY_TOP_PRODUCTS,

    /** The employees with the highest sustained performance over the window. */
    TOP_PERFORMERS,

    /**
     * Shifts that hold neither work nor an absence. The one thing on this board
     * that is a gap in the DATA rather than in the production — and the one that
     * costs the most if it reaches payroll unnoticed.
     */
    MISSING_ENTRIES,

    /**
     * Operations where employees' results are far apart. Reads as either a norm
     * that does not describe the work, or somebody who needs showing how.
     */
    PERFORMANCE_SPREAD,

    /** Operations whose scrap has risen above their own earlier level. */
    SCRAP_SPIKE
}
