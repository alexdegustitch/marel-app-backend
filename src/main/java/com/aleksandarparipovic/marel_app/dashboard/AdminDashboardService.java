package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.CommercialDashboardQueryRepository.OpenOrderRef;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.DeadlinePressureRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.RegistrationRequestRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.AttentionOrderRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgressSummary;
import com.aleksandarparipovic.marel_app.user_registration_request.UserRegistrationRequestService;
import com.aleksandarparipovic.marel_app.user_registration_request.UserRegistrationRequestStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the direktor's control board.
 *
 * <p>Composed, not invented: the commercial blocks (rokovi, isporuke,
 * popunjenost, odrađeni zahtevi) are asked of {@link CommercialDashboardService}
 * and the supervisor blocks (odsustva, analitika) of
 * {@link SupervisorDashboardService}, through the same package-visible methods
 * those boards use themselves. A figure that appears on two boards has to come
 * from one computation, or the two screens will eventually disagree.
 *
 * <p>The one block of its own is {@code deadlinePressure}: open, scoped orders
 * whose rok is breached or near while the work is under half done — the cross
 * of the commercial rok and the shop floor's progress that only this reader
 * looks at as one thing.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    /** Rows per card. Enough to read at a glance, few enough to stay a summary. */
    private static final int ROWS_PER_BLOCK = 5;

    /** Rows a drawer carries; the badge says how many there are in all. */
    private static final int DRAWER_ROWS = 30;

    /** How far ahead the days-off card counts, for its badge. */
    private static final int CALENDAR_HORIZON_DAYS = 90;

    /**
     * Under this percent, an order near its rok counts as "pod pritiskom".
     * Half, because past half the question changes from "will it be started"
     * to "will it be finished" — and the second is the late/due-soon drawers'
     * ordinary business.
     */
    private static final int PRESSURE_MAX_PERCENT = 50;

    private final DashboardQueryRepository queryRepository;
    private final UserRegistrationRequestService registrationRequestService;
    private final CommercialDashboardService commercialDashboardService;
    private final SupervisorDashboardService supervisorDashboardService;

    @Transactional(readOnly = true)
    public AdminDashboardResponse load() {
        LocalDate today = LocalDate.now();
        OffsetDateTime deliveredSince = OffsetDateTime.now()
                .minusDays(CommercialDashboardService.DELIVERED_WINDOW_DAYS);
        OffsetDateTime requestsSince = OffsetDateTime.now()
                .minusDays(CommercialDashboardService.REQUESTS_WINDOW_DAYS);

        Block<AttentionOrderRow> late = commercialDashboardService.attentionBlock("LATE", today);
        Block<AttentionOrderRow> dueSoon = commercialDashboardService.attentionBlock("DUE_SOON", today);

        List<OpenOrderRef> openBook = commercialDashboardService.openBook();
        Map<Long, OrderProgressSummary> summaries = commercialDashboardService.openBookSummaries(openBook);

        return new AdminDashboardResponse(
                today,
                CommercialDashboardService.DELIVERED_WINDOW_DAYS,
                CommercialDashboardService.REQUESTS_WINDOW_DAYS,
                CommercialDashboardService.kpis(openBook, summaries),
                late,
                dueSoon,
                commercialDashboardService.deliveredBlock(deliveredSince),
                CommercialDashboardService.progressOverview(openBook, summaries),
                commercialDashboardService.recentRequests(requestsSince),
                Block.of(
                        queryRepository.countNonWorkingDaysBetween(
                                today, today.plusDays(CALENDAR_HORIZON_DAYS)),
                        queryRepository.findUpcomingNonWorkingDays(today, ROWS_PER_BLOCK)),
                supervisorDashboardService.absences(today),
                registrationRequests(),
                Block.of(
                        queryRepository.countReadyPayrolls(),
                        queryRepository.findReadyPayrolls(DRAWER_ROWS)),
                deadlinePressure(late, dueSoon, summaries),
                supervisorDashboardService.insights(today));
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
     * Production orders from the late and due-soon drawers whose agreed razrada
     * is under {@value #PRESSURE_MAX_PERCENT} % done — most urgent rok first.
     *
     * <p>Built from the blocks already computed rather than a query of its own,
     * so a row here IS a row of those drawers: same effective deadline, same
     * progress funnel. An order without an agreed razrada is not on this card —
     * it has no percent to be under, and "bez razrade" is its own card.
     */
    private Block<DeadlinePressureRow> deadlinePressure(
            Block<AttentionOrderRow> late,
            Block<AttentionOrderRow> dueSoon,
            Map<Long, OrderProgressSummary> summaries) {

        // LinkedHashMap keyed by id: late first (already worst-first), and an
        // order that managed to be in both drawers is counted once.
        Map<Long, AttentionOrderRow> candidates = new LinkedHashMap<>();
        for (AttentionOrderRow row : late.rows()) {
            if (CommercialDashboardResponse.KIND_PRODUCTION.equals(row.kind())) {
                candidates.putIfAbsent(row.id(), row);
            }
        }
        for (AttentionOrderRow row : dueSoon.rows()) {
            if (CommercialDashboardResponse.KIND_PRODUCTION.equals(row.kind())) {
                candidates.putIfAbsent(row.id(), row);
            }
        }

        List<DeadlinePressureRow> pressed = new ArrayList<>();
        for (AttentionOrderRow row : candidates.values()) {
            OrderProgressSummary summary = summaries.get(row.id());
            if (summary == null || !summary.scopeDefined() || summary.percent() == null) {
                continue;
            }
            if (summary.percent().compareTo(BigDecimal.valueOf(PRESSURE_MAX_PERCENT)) >= 0) {
                continue;
            }
            pressed.add(new DeadlinePressureRow(
                    row.id(), row.code(), row.name(), row.customerName(),
                    row.deadlineDate(), row.daysLeft(),
                    summary.percent(), summary.donePieces(), summary.requiredPieces()));
        }

        pressed.sort(Comparator.comparingLong(DeadlinePressureRow::daysLeft)
                .thenComparing(DeadlinePressureRow::percent)
                .thenComparing(DeadlinePressureRow::id, Comparator.reverseOrder()));

        return Block.of(
                pressed.size(),
                pressed.size() > ROWS_PER_BLOCK ? pressed.subList(0, ROWS_PER_BLOCK) : pressed);
    }
}
