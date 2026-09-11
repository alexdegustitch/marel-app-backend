package com.aleksandarparipovic.marel_app.dashboard.dto;

import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.Block;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the commercial board shows, in one answer.
 *
 * <p>Same doctrine as the other boards: one fat response, every block capped and
 * carrying its own total. The KPI drawers ("koji su TAČNO ti nalozi") read their
 * rows from here rather than opening a second connection per click — a clicked
 * tile has to open on the same numbers it advertised.
 *
 * <p>Order rows come in two kinds — production and sample — because the reader
 * sells both. {@code kind} says which screen a row's link opens; the figures are
 * whatever that kind actually has (a sample order has no progress, an order
 * without an agreed razrada has no percent).
 */
public record CommercialDashboardResponse(
        /** The server's today. Every daysLeft figure below counts from it. */
        LocalDate today,

        /** How far back "isporučeno nedavno" reaches, in days. */
        int deliveredWindowDays,

        /** How far back "nedavno odrađeni zahtevi" reaches, in days. */
        int requestsWindowDays,

        /** The figures no block carries: the progress overview of the open book. */
        Kpis kpis,

        /** Open orders past their rok, most overdue first. Both kinds. */
        Block<AttentionOrderRow> late,

        /** Open orders due within three days, soonest first. Both kinds. */
        Block<AttentionOrderRow> dueSoon,

        /** Delivered / closed within the window, newest first. Both kinds. */
        Block<DeliveredOrderRow> delivered,

        /** The open production orders ranked by how filled they are. */
        ProgressOverview progress,

        /** The caller's own production orders, newest first. */
        Block<MyOrderRow> myProductionOrders,

        /** The caller's own sample orders, newest first. */
        Block<MyOrderRow> mySampleOrders,

        /** Requests answered within the window, newest answer first. Both workflows. */
        Block<CompletedRequestRow> recentRequests
) {

    /** Which order screen a row belongs to. */
    public static final String KIND_PRODUCTION = "PRODUCTION";
    public static final String KIND_SAMPLE = "SAMPLE";

    /** Which request workflow answered. */
    public static final String REQUEST_MANUFACTURING_TIME = "MANUFACTURING_TIME";
    public static final String REQUEST_SCOPE = "RAZRADA";

    /**
     * The progress figures of the whole open book.
     *
     * <p>{@code averagePercent} is the mean over open production orders WITH an
     * agreed razrada — an order without one has no denominator and would drag
     * the average toward a number nobody agreed on. Null when nothing is scoped.
     */
    public record Kpis(
            long openProductionOrders,
            long openWithScope,
            BigDecimal averagePercent
    ) {}

    /** An open order and how its rok stands. {@code daysLeft} is negative when late. */
    public record AttentionOrderRow(
            String kind,
            Long id,
            String code,
            String name,
            String customerName,
            LocalDate deadlineDate,
            long daysLeft,
            boolean highPriority
    ) {}

    /**
     * An order delivered (production) or closed (sample) within the window.
     *
     * <p>{@code deliveredAt} is the row's last change, because neither table
     * keeps a delivery timestamp — the status flip is the last thing that
     * happens to an order, so in practice this IS the delivery moment, but an
     * order edited after delivery will show the edit's date.
     */
    public record DeliveredOrderRow(
            String kind,
            Long id,
            String code,
            String name,
            String customerName,
            OffsetDateTime deliveredAt
    ) {}

    /** The most- and least-filled ends of the open production orders. */
    public record ProgressOverview(
            List<ProgressRow> mostFilled,
            List<ProgressRow> leastFilled
    ) {}

    /** One open, scoped production order and how much of it is done. */
    public record ProgressRow(
            Long id,
            String code,
            String name,
            String customerName,
            BigDecimal percent,
            long donePieces,
            long requiredPieces
    ) {}

    /**
     * One of the caller's own orders. {@code percent} and {@code scopeDefined}
     * are null for a sample order — samples have no razrada to measure against.
     */
    public record MyOrderRow(
            String kind,
            Long id,
            String code,
            String name,
            String customerName,
            String status,
            LocalDate deadlineDate,
            boolean highPriority,
            BigDecimal percent,
            Boolean scopeDefined
    ) {}

    /**
     * A request answered lately — taken and completed, with where its answer
     * lives. For a vreme-izrade request that is the manufacturing-time record
     * ({@code resultManufacturingTimeId}); for a razrada it is the request
     * itself, read by {@code id}.
     */
    public record CompletedRequestRow(
            String kind,
            Long id,
            /** The subject: the product (vreme izrade) — null for a razrada. */
            Long productId,
            String productName,
            /** The order the request was raised on; null when standalone. */
            Long productionOrderId,
            String productionOrderCode,
            String productionOrderName,
            Long sampleOrderId,
            String sampleOrderCode,
            String createdByName,
            String processedByName,
            OffsetDateTime processedAt,
            Long resultManufacturingTimeId
    ) {}
}
