package com.aleksandarparipovic.marel_app.dashboard.dto;

import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NonWorkingDayRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.MissingEntryRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NoNormRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NormFitRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.OperationVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.PerformerRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ProductVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ScrapRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.SpreadRow;

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

        /** Requests somebody took and has not finished. */
        Block<RequestRow> claimedRequests,

        Block<NonWorkingDayRow> upcomingNonWorkingDays,

        AbsenceBlock absences,

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
     * Who is absent on a sick-leave code today.
     *
     * @param configured false when nobody has said which work codes mean sick leave.
     *                   The card then says so, instead of reporting an empty list as
     *                   though it were good news.
     */
    public record AbsenceBlock(
            boolean configured,
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
            LocalDate yesterday,
            List<NormFitRow> normTooLow,
            List<NormFitRow> normTooHigh,
            List<NoNormRow> noNormHighVolume,
            List<OperationVolumeRow> mostWorkedOperations,
            List<OperationVolumeRow> leastWorkedOperations,
            List<OperationVolumeRow> yesterdayOperations,
            List<ProductVolumeRow> yesterdayProducts,
            List<PerformerRow> topPerformers,
            List<MissingEntryRow> missingEntries,
            List<SpreadRow> performanceSpread,
            List<ScrapRow> scrapSpike
    ) {

        /** What the board shows before the job has ever run. */
        public static Insights notComputedYet(int windowDays, LocalDate yesterday) {
            return new Insights(null, null, true, windowDays, yesterday,
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
