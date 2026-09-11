package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;
import com.aleksandarparipovic.marel_app.dashboard.dto.MissingShiftsResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.AbsenceBlock;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.Insights;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightComputeService;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightKey;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightRepository;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.MissingEntryRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NoNormRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NormFitRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.OperationVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.OrderVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.PerformerRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ProductVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ScrapRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.SpreadRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Composes the supervisor's control board.
 *
 * <p>Read-only, and split down the middle. The live half asks the database the
 * five bounded questions whose answers must be current. The analytical half asks
 * nothing at all — it reads the morning's snapshot, which is the whole reason the
 * heavy questions can be on a home screen in the first place.
 *
 * <p>The "recently worked on" blocks are scoped to the caller. That is a product
 * decision, not a security one: the point of those two cards is "where did I stop",
 * so they are the caller's trail, not the factory's.
 */
@Service
@RequiredArgsConstructor
public class SupervisorDashboardService {

    /** Rows per card. */
    private static final int ROWS_PER_BLOCK = 5;

    /** How far back "nedavno rađeno" reaches. */
    private static final int WINDOW_DAYS = 30;

    /** How far ahead the days-off card counts, for its badge. Same as the admin board. */
    private static final int CALENDAR_HORIZON_DAYS = 90;

    /**
     * The missing-shifts drawer lists the whole factory, so this is a guard
     * against an absurd read rather than a page size.
     */
    private static final int MISSING_SHIFT_ROWS = 500;

    /** The entry-gaps worklist: enough to work through, not the whole history. */
    private static final int ENTRY_GAP_ROWS = 12;

    private final SupervisorDashboardQueryRepository queryRepository;
    private final DashboardQueryRepository adminQueryRepository;
    private final DashboardInsightRepository insightRepository;
    private final DashboardInsightComputeService computeService;

    @Transactional(readOnly = true)
    public SupervisorDashboardResponse load(Long currentUserId) {
        LocalDate today = LocalDate.now();
        OffsetDateTime since = OffsetDateTime.now().minusDays(WINDOW_DAYS);

        return new SupervisorDashboardResponse(
                today,
                WINDOW_DAYS,
                Block.of(
                        queryRepository.countMyRecentRecords(currentUserId, since),
                        queryRepository.findMyRecentRecords(currentUserId, since, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countMyRecentPayrolls(currentUserId, since),
                        queryRepository.findMyRecentPayrolls(currentUserId, since, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countOpenRequests("PENDING"),
                        queryRepository.findOpenRequests("PENDING", currentUserId, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countOpenRequests("IN_REVIEW"),
                        queryRepository.findOpenRequests("IN_REVIEW", currentUserId, ROWS_PER_BLOCK)),
                Block.of(
                        adminQueryRepository.countNonWorkingDaysBetween(
                                today, today.plusDays(CALENDAR_HORIZON_DAYS)),
                        adminQueryRepository.findUpcomingNonWorkingDays(today, ROWS_PER_BLOCK)),
                absences(today),
                readyRecords(today),
                missingShiftsBlock(today),
                Block.of(
                        queryRepository.countEntryGaps(),
                        queryRepository.findEntryGaps(ENTRY_GAP_ROWS)),
                insights(today));
    }

    /**
     * The card's count: who is employed today and has no shift entered. Sunday
     * gets {@code applicable = false} instead of a factory-wide count — shifts
     * are not required then, and a board that shouts "everyone is missing" every
     * Sunday would teach people to ignore the card.
     */
    private SupervisorDashboardResponse.MissingShiftsBlock missingShiftsBlock(LocalDate day) {
        if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return new SupervisorDashboardResponse.MissingShiftsBlock(false, 0);
        }
        return new SupervisorDashboardResponse.MissingShiftsBlock(
                true, queryRepository.countEmployeesWithoutShift(day));
    }

    /**
     * The names behind the count, read at the moment the drawer opens. Live on
     * purpose: this is a worklist somebody acts on row by row, and a colleague
     * may have entered one of the shifts since the board loaded.
     */
    @Transactional(readOnly = true)
    public MissingShiftsResponse missingShifts(LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return new MissingShiftsResponse(day, false, 0, List.of());
        }
        return new MissingShiftsResponse(
                day,
                true,
                queryRepository.countEmployeesWithoutShift(day),
                queryRepository.findEmployeesWithoutShift(day, MISSING_SHIFT_ROWS));
    }

    /**
     * Who is on sick leave or godišnji odmor today. The categories declare it
     * themselves (type SICK_LEAVE, plus GO) — the old code-list setting is gone
     * because V39 made the schema able to answer the question.
     */
    private AbsenceBlock absences(LocalDate today) {
        return new AbsenceBlock(
                queryRepository.countAbsentOn(today),
                queryRepository.findAbsentOn(
                        today, today.minusDays(WINDOW_DAYS - 1L), ROWS_PER_BLOCK));
    }

    /**
     * Whose PREVIOUS month is fully entered — the "obračuni spremni za predaju"
     * card. Previous month, because that is the month being handed to payroll;
     * the current one cannot be complete before it ends.
     */
    private SupervisorDashboardResponse.ReadyRecordsBlock readyRecords(LocalDate today) {
        YearMonth month = YearMonth.from(today).minusMonths(1);
        List<SupervisorDashboardQueryRepository.RecordReadiness> all =
                queryRepository.findRecordReadiness(month.atDay(1), month.atEndOfMonth());

        List<SupervisorDashboardResponse.ReadyRecordRow> ready = all.stream()
                .filter(r -> r.requiredDays() > 0
                        && r.missingDays() == 0
                        && r.employeeRecordId() != null)
                .map(r -> new SupervisorDashboardResponse.ReadyRecordRow(
                        r.employeeId(), r.fullName(), r.employeeRecordId()))
                .toList();

        return new SupervisorDashboardResponse.ReadyRecordsBlock(
                month.getYear(),
                month.getMonthValue(),
                ready.size(),
                all.size(),
                ready.stream().limit(ROWS_PER_BLOCK).toList());
    }

    /**
     * The morning's analytics, read back.
     *
     * <p>Every key is read on its own and the day is taken from the first one that
     * has an answer, so a snapshot half-written by a job that failed part way still
     * shows what it managed rather than nothing.
     */
    private Insights insights(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        // The criteria as tuned right now. Changing one in Parametri recomputes
        // the snapshot, so what the hints SAY and what the rows MET stay one.
        DashboardInsightComputeService.Thresholds thresholds = computeService.currentThresholds();

        Optional<DashboardInsightRepository.Stored<NormFitRow>> normTooLow =
                insightRepository.findLatest(DashboardInsightKey.NORM_TOO_LOW, NormFitRow.class);

        LocalDate computedFor = normTooLow.map(DashboardInsightRepository.Stored::computedFor).orElse(null);
        OffsetDateTime computedAt = normTooLow.map(DashboardInsightRepository.Stored::computedAt).orElse(null);

        if (computedFor == null) {
            return Insights.notComputedYet(DashboardInsightComputeService.WINDOW_DAYS, thresholds, yesterday);
        }

        return new Insights(
                computedFor,
                computedAt,
                !today.equals(computedFor),
                DashboardInsightComputeService.WINDOW_DAYS,
                thresholds.normWindowDays(),
                thresholds.normRisePct(),
                thresholds.normDropPct(),
                thresholds.activityWindowDays(),
                thresholds.topPerformerMinHours(),
                computedFor.minusDays(1),
                normTooLow.map(DashboardInsightRepository.Stored::rows).orElseGet(List::of),
                rows(DashboardInsightKey.NORM_TOO_HIGH, NormFitRow.class),
                rows(DashboardInsightKey.NO_NORM_HIGH_VOLUME, NoNormRow.class),
                rows(DashboardInsightKey.MOST_WORKED_OPERATIONS, OperationVolumeRow.class),
                rows(DashboardInsightKey.LEAST_WORKED_OPERATIONS, OperationVolumeRow.class),
                rows(DashboardInsightKey.YESTERDAY_TOP_OPERATIONS, OperationVolumeRow.class),
                rows(DashboardInsightKey.YESTERDAY_TOP_PRODUCTS, ProductVolumeRow.class),
                rows(DashboardInsightKey.YESTERDAY_TOP_ORDERS, OrderVolumeRow.class),
                rows(DashboardInsightKey.YESTERDAY_TOP_PERFORMERS, PerformerRow.class),
                rows(DashboardInsightKey.TOP_PERFORMERS, PerformerRow.class),
                rows(DashboardInsightKey.MISSING_ENTRIES, MissingEntryRow.class),
                rows(DashboardInsightKey.PERFORMANCE_SPREAD, SpreadRow.class),
                rows(DashboardInsightKey.SCRAP_SPIKE, ScrapRow.class));
    }

    private <T> List<T> rows(DashboardInsightKey key, Class<T> rowType) {
        return insightRepository.findLatest(key, rowType)
                .map(DashboardInsightRepository.Stored::rows)
                .orElseGet(List::of);
    }
}
