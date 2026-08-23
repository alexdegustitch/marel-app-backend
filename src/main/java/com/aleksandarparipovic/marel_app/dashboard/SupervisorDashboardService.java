package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.app_settings.AppSetting;
import com.aleksandarparipovic.marel_app.app_settings.AppSettingRepository;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;
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
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.PerformerRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ProductVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ScrapRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.SpreadRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
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

    /** Which work codes mean sick leave. Seeded empty; the factory fills it in. */
    static final String SICK_LEAVE_SETTING_KEY = "sick_leave_work_code_category_nos";

    private final SupervisorDashboardQueryRepository queryRepository;
    private final DashboardQueryRepository adminQueryRepository;
    private final DashboardInsightRepository insightRepository;
    private final AppSettingRepository appSettingRepository;

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
                insights(today));
    }

    /**
     * Who is out sick today.
     *
     * <p>Returns {@code configured = false} rather than an empty list when the
     * setting is blank. The two are not the same thing and the screen must not say
     * "nobody is absent" when what is true is "nobody has said what absence looks
     * like".
     */
    private AbsenceBlock absences(LocalDate today) {
        List<String> categoryNos = sickLeaveCategoryNos();
        if (categoryNos.isEmpty()) {
            return new AbsenceBlock(false, 0, List.of());
        }

        return new AbsenceBlock(
                true,
                queryRepository.countAbsentOn(today, categoryNos),
                queryRepository.findAbsentOn(
                        today, categoryNos, today.minusDays(WINDOW_DAYS - 1L), ROWS_PER_BLOCK));
    }

    /** The configured codes, split and trimmed; empty when nothing is set. */
    private List<String> sickLeaveCategoryNos() {
        return appSettingRepository
                .findCurrentByKey(SICK_LEAVE_SETTING_KEY, OffsetDateTime.now())
                .map(AppSetting::getSettingValueText)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(part -> !part.isEmpty())
                        .toList())
                .orElseGet(List::of);
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

        Optional<DashboardInsightRepository.Stored<NormFitRow>> normTooLow =
                insightRepository.findLatest(DashboardInsightKey.NORM_TOO_LOW, NormFitRow.class);

        LocalDate computedFor = normTooLow.map(DashboardInsightRepository.Stored::computedFor).orElse(null);
        OffsetDateTime computedAt = normTooLow.map(DashboardInsightRepository.Stored::computedAt).orElse(null);

        if (computedFor == null) {
            return Insights.notComputedYet(DashboardInsightComputeService.WINDOW_DAYS, yesterday);
        }

        return new Insights(
                computedFor,
                computedAt,
                !today.equals(computedFor),
                DashboardInsightComputeService.WINDOW_DAYS,
                computedFor.minusDays(1),
                normTooLow.map(DashboardInsightRepository.Stored::rows).orElseGet(List::of),
                rows(DashboardInsightKey.NORM_TOO_HIGH, NormFitRow.class),
                rows(DashboardInsightKey.NO_NORM_HIGH_VOLUME, NoNormRow.class),
                rows(DashboardInsightKey.MOST_WORKED_OPERATIONS, OperationVolumeRow.class),
                rows(DashboardInsightKey.LEAST_WORKED_OPERATIONS, OperationVolumeRow.class),
                rows(DashboardInsightKey.YESTERDAY_TOP_OPERATIONS, OperationVolumeRow.class),
                rows(DashboardInsightKey.YESTERDAY_TOP_PRODUCTS, ProductVolumeRow.class),
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
