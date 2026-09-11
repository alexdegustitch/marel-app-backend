package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.CommercialDashboardQueryRepository.OpenOrderRef;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.AttentionOrderRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.CompletedRequestRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.DeliveredOrderRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.Kpis;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.MyOrderRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.ProgressOverview;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.ProgressRow;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCardRow;
import com.aleksandarparipovic.marel_app.production_order_progress.OrderProgressService;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgressSummary;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrderService;
import com.aleksandarparipovic.marel_app.sample_order.dto.SampleOrderCardRow;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Composes the commercial board.
 *
 * <p>Read-only, and deliberately thin on SQL of its own. What "late" or "due
 * soon" means for a production order — the effective deadline over the order's
 * windows AND its line items — already lives in {@link ProductionOrderService},
 * behind the same attention pseudo-filter the order board's KPI tiles send. This
 * service sends that filter programmatically, so a tile here and a tile on the
 * board can never disagree about which orders they mean. Progress goes through
 * {@link OrderProgressService}, the single funnel, for the same reason.
 *
 * <p>The "moji nalozi" blocks are scoped to the caller: they are the reader's
 * own book. Everything else is the whole factory's — a rok is a rok no matter
 * whose order breaks it.
 */
@Service
@RequiredArgsConstructor
public class CommercialDashboardService {

    /** Rows a KPI drawer carries; the badge says how many there are in all. */
    private static final int DRAWER_ROWS = 30;

    /** Rows per end of the progress ranking. */
    private static final int RANK_ROWS = 8;

    /** Rows per "moji nalozi" tab. */
    private static final int MY_ORDER_ROWS = 7;

    /** Answered requests carried along; the badge says the true total. */
    private static final int REQUEST_ROWS = 30;

    /** How far back "isporučeno nedavno" reaches. */
    private static final int DELIVERED_WINDOW_DAYS = 30;

    /** How far back "nedavno odrađeni zahtevi" reaches. */
    private static final int REQUESTS_WINDOW_DAYS = 7;

    /**
     * The ranking reads the whole open book, so this only guards against an
     * absurd one — the same posture the order board's in-memory path takes.
     */
    private static final int OPEN_BOOK_CAP = 1000;

    private final CommercialDashboardQueryRepository queryRepository;
    private final ProductionOrderService productionOrderService;
    private final SampleOrderService sampleOrderService;
    private final OrderProgressService orderProgressService;

    @Transactional(readOnly = true)
    public CommercialDashboardResponse load(Long currentUserId) {
        LocalDate today = LocalDate.now();
        OffsetDateTime deliveredSince = OffsetDateTime.now().minusDays(DELIVERED_WINDOW_DAYS);
        OffsetDateTime requestsSince = OffsetDateTime.now().minusDays(REQUESTS_WINDOW_DAYS);

        Block<AttentionOrderRow> late = attentionBlock("LATE", today);
        Block<AttentionOrderRow> dueSoon = attentionBlock("DUE_SOON", today);
        Block<DeliveredOrderRow> delivered = deliveredBlock(deliveredSince);

        List<OpenOrderRef> openBook = queryRepository.findOpenProductionOrders(OPEN_BOOK_CAP);
        Map<Long, OrderProgressSummary> summaries = openBook.isEmpty()
                ? Map.of()
                : orderProgressService.summaries(openBook.stream().map(OpenOrderRef::id).toList());

        return new CommercialDashboardResponse(
                today,
                DELIVERED_WINDOW_DAYS,
                REQUESTS_WINDOW_DAYS,
                kpis(openBook, summaries),
                late,
                dueSoon,
                delivered,
                progressOverview(openBook, summaries),
                myProductionOrders(currentUserId),
                mySampleOrders(currentUserId),
                recentRequests(requestsSince));
    }

    // ── Late / due soon, both kinds, one list ───────────────────────────────

    /**
     * Both order kinds in one attention state, worst first.
     *
     * <p>Each side is asked through its own {@code searchAll} with the board's
     * attention pseudo-filter, so the rows here are exactly the rows the order
     * boards' KPI tiles narrow to. The two pages are merged by how the rok
     * stands; the total is the sum of the two true totals, not of what fit.
     */
    private Block<AttentionOrderRow> attentionBlock(String attention, LocalDate today) {
        Page<ProductionOrderCardRow> production = productionOrderService.searchAll(
                searchRequest(DRAWER_ROWS,
                        List.of(filter("attention", SearchRequest.Operator.EQ, attention)),
                        List.of(sort("deliveryDeadline", SearchRequest.Direction.ASC))));

        Page<SampleOrderCardRow> samples = sampleOrderService.searchAll(
                searchRequest(DRAWER_ROWS,
                        List.of(filter("attention", SearchRequest.Operator.EQ, attention)),
                        List.of(sort("deadlineDate", SearchRequest.Direction.ASC))));

        List<AttentionOrderRow> rows = new ArrayList<>();
        for (ProductionOrderCardRow row : production.getContent()) {
            rows.add(new AttentionOrderRow(
                    CommercialDashboardResponse.KIND_PRODUCTION,
                    row.id(), row.code(), row.name(), row.customerName(),
                    row.effectiveDeadlineDate(),
                    daysLeft(today, row.effectiveDeadlineDate()),
                    Boolean.TRUE.equals(row.isHighPriority())));
        }
        for (SampleOrderCardRow row : samples.getContent()) {
            rows.add(new AttentionOrderRow(
                    CommercialDashboardResponse.KIND_SAMPLE,
                    row.id(), row.code(), row.name(), row.customerName(),
                    row.deadlineDate(),
                    daysLeft(today, row.deadlineDate()),
                    false));
        }

        rows.sort(Comparator.comparingLong(AttentionOrderRow::daysLeft)
                .thenComparing(row -> !row.highPriority())
                .thenComparing(AttentionOrderRow::id, Comparator.reverseOrder()));

        return Block.of(
                production.getTotalElements() + samples.getTotalElements(),
                rows.size() > DRAWER_ROWS ? rows.subList(0, DRAWER_ROWS) : rows);
    }

    private Block<DeliveredOrderRow> deliveredBlock(OffsetDateTime since) {
        List<DeliveredOrderRow> rows = new ArrayList<>();
        rows.addAll(queryRepository.findDeliveredProductionOrders(since, DRAWER_ROWS));
        rows.addAll(queryRepository.findClosedSampleOrders(since, DRAWER_ROWS));
        rows.sort(Comparator.comparing(DeliveredOrderRow::deliveredAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DeliveredOrderRow::id, Comparator.reverseOrder()));

        return Block.of(
                queryRepository.countDeliveredProductionOrders(since)
                        + queryRepository.countClosedSampleOrders(since),
                rows.size() > DRAWER_ROWS ? rows.subList(0, DRAWER_ROWS) : rows);
    }

    // ── Progress ────────────────────────────────────────────────────────────

    /**
     * The average over the open, SCOPED production orders. An order without an
     * agreed razrada has no denominator, so it is out of the average rather
     * than in it as a zero nobody agreed on.
     */
    private static Kpis kpis(List<OpenOrderRef> openBook, Map<Long, OrderProgressSummary> summaries) {
        List<BigDecimal> percents = openBook.stream()
                .map(ref -> summaries.get(ref.id()))
                .filter(s -> s != null && s.scopeDefined() && s.percent() != null)
                .map(OrderProgressSummary::percent)
                .toList();

        BigDecimal average = percents.isEmpty()
                ? null
                : percents.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(percents.size()), 1, RoundingMode.HALF_UP);

        return new Kpis(openBook.size(), percents.size(), average);
    }

    /** The two ends of the ranking: fullest first on one, emptiest first on the other. */
    private static ProgressOverview progressOverview(
            List<OpenOrderRef> openBook, Map<Long, OrderProgressSummary> summaries) {

        List<ProgressRow> scoped = openBook.stream()
                .map(ref -> {
                    OrderProgressSummary summary = summaries.get(ref.id());
                    if (summary == null || !summary.scopeDefined() || summary.percent() == null) {
                        return null;
                    }
                    return new ProgressRow(
                            ref.id(), ref.code(), ref.name(), ref.customerName(),
                            summary.percent(), summary.donePieces(), summary.requiredPieces());
                })
                .filter(row -> row != null)
                .toList();

        Comparator<ProgressRow> byPercentDesc = Comparator
                .comparing(ProgressRow::percent, Comparator.reverseOrder())
                .thenComparing(ProgressRow::id, Comparator.reverseOrder());

        List<ProgressRow> most = scoped.stream().sorted(byPercentDesc).limit(RANK_ROWS).toList();
        List<ProgressRow> least = scoped.stream().sorted(byPercentDesc.reversed()).limit(RANK_ROWS).toList();

        return new ProgressOverview(most, least);
    }

    // ── The caller's own book ───────────────────────────────────────────────

    private Block<MyOrderRow> myProductionOrders(Long currentUserId) {
        Page<ProductionOrderCardRow> page = productionOrderService.searchAll(
                searchRequest(MY_ORDER_ROWS,
                        List.of(filter("userId", SearchRequest.Operator.EQ, String.valueOf(currentUserId))),
                        List.of(sort("creationDate", SearchRequest.Direction.DESC))));

        List<Long> ids = page.getContent().stream().map(ProductionOrderCardRow::id).toList();
        Map<Long, OrderProgressSummary> summaries =
                ids.isEmpty() ? Map.of() : orderProgressService.summaries(ids);

        List<MyOrderRow> rows = page.getContent().stream()
                .map(row -> {
                    OrderProgressSummary summary = summaries.get(row.id());
                    return new MyOrderRow(
                            CommercialDashboardResponse.KIND_PRODUCTION,
                            row.id(), row.code(), row.name(), row.customerName(),
                            row.status() == null ? null : row.status().name(),
                            row.effectiveDeadlineDate(),
                            Boolean.TRUE.equals(row.isHighPriority()),
                            summary == null ? null : summary.percent(),
                            summary == null ? null : summary.scopeDefined());
                })
                .toList();

        return Block.of(page.getTotalElements(), rows);
    }

    private Block<MyOrderRow> mySampleOrders(Long currentUserId) {
        Page<SampleOrderCardRow> page = sampleOrderService.searchAll(
                searchRequest(MY_ORDER_ROWS,
                        List.of(filter("userId", SearchRequest.Operator.EQ, String.valueOf(currentUserId))),
                        List.of(sort("creationDate", SearchRequest.Direction.DESC))));

        List<MyOrderRow> rows = page.getContent().stream()
                .map(row -> new MyOrderRow(
                        CommercialDashboardResponse.KIND_SAMPLE,
                        row.id(), row.code(), row.name(), row.customerName(),
                        row.status(),
                        row.deadlineDate(),
                        false,
                        null,
                        null))
                .toList();

        return Block.of(page.getTotalElements(), rows);
    }

    // ── Requests answered lately ────────────────────────────────────────────

    private Block<CompletedRequestRow> recentRequests(OffsetDateTime since) {
        List<CompletedRequestRow> rows = new ArrayList<>();
        rows.addAll(queryRepository.findCompletedTimeRequests(since, REQUEST_ROWS));
        rows.addAll(queryRepository.findCompletedScopeRequests(since, REQUEST_ROWS));
        rows.sort(Comparator.comparing(CompletedRequestRow::processedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CompletedRequestRow::id, Comparator.reverseOrder()));

        return Block.of(
                queryRepository.countCompletedTimeRequests(since)
                        + queryRepository.countCompletedScopeRequests(since),
                rows.size() > REQUEST_ROWS ? rows.subList(0, REQUEST_ROWS) : rows);
    }

    // ── SearchRequest assembly ──────────────────────────────────────────────

    private static long daysLeft(LocalDate today, LocalDate deadline) {
        return deadline == null ? 0 : ChronoUnit.DAYS.between(today, deadline);
    }

    private static SearchRequest searchRequest(
            int size, List<SearchRequest.FilterField> filters, List<SearchRequest.SortField> sort) {
        SearchRequest request = new SearchRequest();
        SearchRequest.Pagination pagination = new SearchRequest.Pagination();
        pagination.setPage(0);
        pagination.setSize(size);
        request.setPagination(pagination);
        request.setFilters(new ArrayList<>(filters));
        request.setSort(new ArrayList<>(sort));
        return request;
    }

    private static SearchRequest.FilterField filter(
            String field, SearchRequest.Operator operator, Object value) {
        SearchRequest.FilterField filterField = new SearchRequest.FilterField();
        filterField.setField(field);
        filterField.setOperator(operator);
        filterField.setValue(value);
        return filterField;
    }

    private static SearchRequest.SortField sort(String field, SearchRequest.Direction direction) {
        SearchRequest.SortField sortField = new SearchRequest.SortField();
        sortField.setField(field);
        sortField.setDirection(direction);
        return sortField;
    }
}
