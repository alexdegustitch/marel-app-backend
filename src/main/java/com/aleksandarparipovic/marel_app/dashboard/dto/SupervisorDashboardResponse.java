package com.aleksandarparipovic.marel_app.dashboard.dto;

import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NonWorkingDayRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.MissingEntryRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NoNormRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NormFitRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.OperationVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.OrderVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.PerformerRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ProductVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ScrapRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.SpreadRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.SuspectEntryRow;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the supervisor's control board shows, in one answer.
 *
 * <p>Two kinds of thing, deliberately kept apart in the shape as well as on the
 * screen. The blocks above {@code insights} are LIVE — read at the moment of the
 * request, because a request that arrived five minutes ago has to appear. The
 * {@code insights} block is a SNAPSHOT computed once that morning, and carries
 * the day it describes so the screen can say so.
 *
 * <p>{@code Block} and {@code NonWorkingDayRow} are the administrator board's own
 * types, reused rather than copied: a card that counts and shows its first few
 * rows means the same thing on both boards, and the calendar is the same calendar.
 *
 * <p>No money anywhere, exactly as on the administrator's board. The payroll card
 * says whose month and which month, never what it comes to.
 */
public record SupervisorDashboardResponse(
        /** The server's today; every "in N days" figure counts from it. */
        LocalDate today,

        /** How far back the "recently worked on" blocks reach. */
        int windowDays,

        /** Kartoni this user themselves last worked on. */
        Block<RecentRecordRow> myRecentRecords,

        /** Obračuni this user themselves last worked on. */
        Block<RecentPayrollRow> myRecentPayrolls,

        /** Manufacturing-time requests nobody has taken yet. */
        Block<RequestRow> pendingRequests,

        /**
         * Requests THIS USER took and has not finished — their own desk, whole
         * (capped only against the absurd). Colleagues' claimed requests live
         * on the requests page.
         */
        Block<RequestRow> claimedRequests,

        Block<NonWorkingDayRow> upcomingNonWorkingDays,

        AbsenceBlock absences,

        /** Whose previous month is fully entered and ready for payroll. */
        ReadyRecordsBlock readyRecords,

        /**
         * How many employed people have no shift entered for today. The count only —
         * the names come through {@code GET /api/dashboard/missing-shifts}, which the
         * board's drawer asks the moment it opens, so the list is live at the moment
         * of acting on it rather than as old as the board.
         */
        MissingShiftsBlock missingShifts,

        /**
         * Live shifts with neither work nor an absence — ALL of history, read
         * live. The snapshot's missingEntries stays the 30-day anomaly view;
         * this is the worklist tile, and an empty shift from two months ago
         * belongs on a worklist however old it is.
         */
        Block<MissingEntryRow> entryGaps,

        Insights insights
) {

    /** A karton, and when this user last had it open. */
    public record RecentRecordRow(
            Long employeeRecordId,
            Long employeeId,
            String employeeName,
            LocalDate periodStart,
            LocalDate periodEnd,
            OffsetDateTime lastActivityAt
    ) {}

    /**
     * A payroll month, and when this user last had it open.
     *
     * @param monthlyReportId what the payroll screen is actually addressed by, so
     *   the card can open the month itself rather than the list it lives in. Null
     *   for an item whose monthly report has not been produced yet — the row is
     *   then shown without a link rather than linking somewhere that cannot load.
     */
    public record RecentPayrollRow(
            Long payrollRunItemId,
            Long monthlyReportId,
            Long employeeId,
            String employeeName,
            LocalDate period,
            String status,
            OffsetDateTime lastActivityAt
    ) {}

    /**
     * A manufacturing-time request that is still moving.
     *
     * @param daysWaiting whole days since it was created — the figure the card is
     *                    sorted and coloured by, since the point of both request
     *                    cards is what has been sitting too long
     * @param assignedToMe true when this user is the one who took it, so the card
     *                     can separate "mine" from "a colleague's" without a second
     *                     query
     */
    public record RequestRow(
            Long id,
            Long productId,
            String productName,
            String requestType,
            String status,
            String requestedByName,
            String assignedToName,
            boolean assignedToMe,
            long daysWaiting,
            OffsetDateTime createdAt
    ) {}

    /**
     * Who is on sick leave or godišnji odmor today — recognised by the
     * category's declared type (V39), not by a code list somebody maintains.
     */
    public record AbsenceBlock(
            long total,
            List<AbsenceRow> rows
    ) {}

    public record AbsenceRow(
            Long employeeId,
            String employeeName,
            String employeeNo,
            String categoryNo,
            String categoryName,
            LocalDate workDate,
            Integer absenceMinutes,
            /** Days inside the window this employee was absent on such a code. */
            Integer daysInWindow
    ) {}

    /**
     * How far the month before this one has been entered — whose karton is
     * complete and can go to payroll.
     *
     * @param readyCount    employees whose every required day of the month holds
     *                      a shift (and whose karton exists)
     * @param employeeCount everybody employed in that month
     */
    public record ReadyRecordsBlock(
            int year,
            int month,
            long readyCount,
            long employeeCount,
            List<ReadyRecordRow> rows
    ) {}

    /**
     * One employee whose month is fully entered.
     *
     * @param monthlyReportId what the payroll screen is addressed by; null while
     *                        the month's report has not been produced yet — the
     *                        row then leads to the payroll list instead.
     */
    public record ReadyRecordRow(
            Long employeeId,
            String fullName,
            Long employeeRecordId,
            Long monthlyReportId
    ) {}

    /**
     * How many employed people the day has no entry for.
     *
     * @param applicable false on Sunday — shifts are not required then, so the
     *                   card is disabled rather than reporting everybody missing
     */
    public record MissingShiftsBlock(
            boolean applicable,
            long total
    ) {}

    /**
     * An employed person with no active shift on the asked-about day.
     *
     * @param employeeRecordId the karton holding the day's month, so the drawer
     *                         can open it directly. Null when that month's karton
     *                         has not been created yet — the row then leads to
     *                         the worker's calendar instead of a dead address.
     */
    public record MissingShiftRow(
            Long employeeId,
            String fullName,
            String employeeNo,
            String departmentName,
            Long employeeRecordId
    ) {}

    /**
     * The morning's analytics, as they were computed.
     *
     * @param computedFor the day the figures describe. Shown on the screen: if the
     *                    job did not run, the board serves the last day that did,
     *                    and the reader has to be able to see which.
     * @param stale       true when {@code computedFor} is not today
     */
    public record Insights(
            LocalDate computedFor,
            OffsetDateTime computedAt,
            boolean stale,
            int windowDays,
            /** The norm cards' window — tunable in Parametri (V44). */
            int normWindowDays,
            /** How far above 100 % "norma je preniska" begins. */
            int normRisePct,
            /** How far below 100 % "norma je previsoka" begins. */
            int normDropPct,
            /** The "Šta se radilo" window — tunable in Parametri (V44). */
            int activityWindowDays,
            /** Hours of recorded normed work before a person is ranked in "Najbolji". */
            int topPerformerMinHours,
            /** The uncapped rate above which an entry is called a probable typo. */
            int suspectRatePct,
            LocalDate yesterday,
            List<NormFitRow> normTooLow,
            List<NormFitRow> normTooHigh,
            List<NoNormRow> noNormHighVolume,
            List<OperationVolumeRow> mostWorkedOperations,
            List<OperationVolumeRow> leastWorkedOperations,
            List<OperationVolumeRow> yesterdayOperations,
            List<ProductVolumeRow> yesterdayProducts,
            List<OrderVolumeRow> yesterdayOrders,
            List<PerformerRow> yesterdayPerformers,
            List<PerformerRow> topPerformers,
            List<MissingEntryRow> missingEntries,
            List<SpreadRow> performanceSpread,
            List<ScrapRow> scrapSpike,
            List<SuspectEntryRow> suspectEntries
    ) {

        /** What the board shows before the job has ever run. */
        public static Insights notComputedYet(
                int windowDays,
                com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightComputeService.Thresholds thresholds,
                LocalDate yesterday) {
            return new Insights(null, null, true, windowDays,
                    thresholds.normWindowDays(), thresholds.normRisePct(), thresholds.normDropPct(),
                    thresholds.activityWindowDays(), thresholds.topPerformerMinHours(),
                    thresholds.suspectRatePct(),
                    yesterday,
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
