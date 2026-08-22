package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductOperationSummaryDto;
import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NormHighlights;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NormRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.RegistrationRequestRow;
import com.aleksandarparipovic.marel_app.user_registration_request.UserRegistrationRequestService;
import com.aleksandarparipovic.marel_app.user_registration_request.UserRegistrationRequestStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Composes the administrator's control board.
 *
 * <p>Read-only, and deliberately thin: every block is either a bounded query in
 * {@link DashboardQueryRepository} or a call to the service that already owns
 * that subject. Registration requests, for instance, come from
 * {@link UserRegistrationRequestService} rather than from a second query of the
 * same table — one place decides what a pending request is.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    /** Rows per card. Enough to read at a glance, few enough to stay a summary. */
    private static final int ROWS_PER_BLOCK = 5;

    /** What "novo" means, for every new-X card alike. */
    private static final int WINDOW_DAYS = 30;

    /** How far ahead the days-off card counts, for its badge. */
    private static final int CALENDAR_HORIZON_DAYS = 90;

    private final DashboardQueryRepository queryRepository;
    private final UserRegistrationRequestService registrationRequestService;
    private final AnalyticsQueryRepository analyticsQueryRepository;

    @Transactional(readOnly = true)
    public AdminDashboardResponse load() {
        LocalDate today = LocalDate.now();
        OffsetDateTime since = OffsetDateTime.now().minusDays(WINDOW_DAYS);

        return new AdminDashboardResponse(
                today,
                WINDOW_DAYS,
                Block.of(
                        queryRepository.countReadyPayrolls(),
                        queryRepository.findReadyPayrolls(ROWS_PER_BLOCK)),
                registrationRequests(),
                Block.of(
                        queryRepository.countUsersSince(since),
                        queryRepository.findNewUsers(since, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countProductsSince(since),
                        queryRepository.findNewProducts(since, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countOperationsSince(since),
                        queryRepository.findNewOperations(since, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countProductionOrdersSince(since),
                        queryRepository.findNewProductionOrders(since, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countSampleOrdersSince(since),
                        queryRepository.findNewSampleOrders(since, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countOpenOrdersWithDeadline(),
                        queryRepository.findNearestDeadlines(today, ROWS_PER_BLOCK)),
                Block.of(
                        queryRepository.countNonWorkingDaysBetween(
                                today, today.plusDays(CALENDAR_HORIZON_DAYS)),
                        queryRepository.findUpcomingNonWorkingDays(today, ROWS_PER_BLOCK)),
                norms(today));
    }

    private Block<RegistrationRequestRow> registrationRequests() {
        List<RegistrationRequestRow> rows = registrationRequestService
                .list(UserRegistrationRequestStatus.PENDING,
                        PageRequest.of(0, ROWS_PER_BLOCK, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(request -> new RegistrationRequestRow(
                        request.id(),
                        request.userId(),
                        request.fullName(),
                        request.roleName(),
                        request.createdAt()))
                .getContent();

        return Block.of(registrationRequestService.countPending(), rows);
    }

    /**
     * The best and worst average performance of the last month, per operation.
     *
     * <p>Asked of the analytics aggregate rather than computed here, so the card
     * and the analytics page answer with the same number — sorting happens in the
     * database across every operation, not over a page that happened to be read.
     *
     * <p>No minimum volume is imposed. An operation logged once can appear at
     * either end, which is why every row carries its pieces and minutes: the card
     * shows what the percentage rests on instead of quietly filtering by a rule
     * nobody agreed on.
     */
    private NormHighlights norms(LocalDate today) {
        LocalDate from = today.minusDays(WINDOW_DAYS);

        return new NormHighlights(
                from,
                today,
                normRows(from, today, "DESC"),
                normRows(from, today, "ASC"));
    }

    private List<NormRow> normRows(LocalDate from, LocalDate to, String direction) {
        AnalyticsFilterRequest filter = new AnalyticsFilterRequest();
        filter.setDateFrom(from);
        filter.setDateTo(to);
        filter.setLevel("OPERATION");
        filter.setSortBy("avgPerformancePct");
        filter.setSortDir(direction);
        filter.setPage(0);
        filter.setSize(ROWS_PER_BLOCK);

        return analyticsQueryRepository.findProductOperationSummaryPage(filter)
                .content()
                .stream()
                .filter(row -> row.getAvgPerformancePct() != null)
                .map(AdminDashboardService::toNormRow)
                .toList();
    }

    private static NormRow toNormRow(ProductOperationSummaryDto row) {
        return new NormRow(
                row.getProductId(),
                row.getProductName(),
                row.getOperationId(),
                row.getOperationName(),
                row.getAvgPerformancePct(),
                row.getSumQuantity(),
                row.getSumDurationMin());
    }
}
