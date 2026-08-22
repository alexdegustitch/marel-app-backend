package com.aleksandarparipovic.marel_app.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the administrator's control board shows, in one answer.
 *
 * <p>One response rather than ten endpoints: the board is a single screen and
 * would otherwise open ten connections on every visit, each with its own loading
 * state and its own chance to fail differently. Every block is capped at a few
 * rows and carries its own total, so the payload stays small no matter how much
 * data is behind it.
 *
 * <p>No money anywhere. The payroll block says WHOSE month is ready and for
 * WHICH month, never what it comes to — amounts are governed by
 * {@code PayrollVisibilityPolicy} on the payroll screens, and a summary card is
 * the wrong place to work around that.
 */
public record AdminDashboardResponse(
        /** The server's today. Every "days until/overdue" figure below counts from it. */
        LocalDate today,

        /** How far back "novo" reaches, in days — the same window for every new-X block. */
        int windowDays,

        Block<ReadyPayrollRow> readyPayrolls,
        Block<RegistrationRequestRow> registrationRequests,
        Block<NewUserRow> newUsers,
        Block<NewProductRow> newProducts,
        Block<NewOperationRow> newOperations,
        Block<NewProductionOrderRow> newProductionOrders,
        Block<NewSampleOrderRow> newSampleOrders,
        Block<OrderDeadlineRow> nearestDeadlines,
        Block<NonWorkingDayRow> upcomingNonWorkingDays,
        NormHighlights norms
) {

    /**
     * A few rows plus how many there are in total.
     *
     * <p>The total is what the card's badge shows; the rows are only the head of
     * the list, so the card can never grow with the data.
     */
    public record Block<T>(long total, List<T> rows) {
        public static <T> Block<T> of(long total, List<T> rows) {
            return new Block<>(total, rows);
        }
    }

    /** A month a supervisor has handed over (status APPROVED) and payroll has not frozen. */
    public record ReadyPayrollRow(
            Long payrollRunItemId,
            Long employeeId,
            String employeeName,
            LocalDate period,
            OffsetDateTime updatedAt
    ) {}

    public record RegistrationRequestRow(
            Long id,
            Long userId,
            String fullName,
            String roleName,
            OffsetDateTime createdAt
    ) {}

    public record NewUserRow(
            Long id,
            String fullName,
            String username,
            String roleName,
            String accountStatus,
            OffsetDateTime createdAt
    ) {}

    public record NewProductRow(
            Long id,
            String productName,
            String productCode,
            OffsetDateTime createdAt
    ) {}

    public record NewOperationRow(
            Long id,
            String operationName,
            Long productId,
            String productName,
            OffsetDateTime createdAt
    ) {}

    public record NewProductionOrderRow(
            Long id,
            String code,
            String name,
            String status,
            OffsetDateTime createdAt
    ) {}

    public record NewSampleOrderRow(
            Long id,
            String name,
            LocalDate deadlineDate,
            OffsetDateTime createdAt
    ) {}

    /**
     * An undelivered order and its nearest deadline.
     *
     * <p>{@code daysLeft} goes negative for an order already past its date — one
     * that is late is more urgent than one that is due, so it belongs at the top
     * of this list rather than filtered out of it.
     */
    public record OrderDeadlineRow(
            Long id,
            String code,
            String name,
            LocalDate deadlineDate,
            long daysLeft,
            boolean highPriority
    ) {}

    public record NonWorkingDayRow(
            LocalDate date,
            String dayType,
            String label,
            long daysUntil
    ) {}

    /**
     * The best and worst average performance of the last month.
     *
     * <p>Each row carries the minutes and pieces it was computed from on purpose:
     * an operation logged once can top or bottom this list, and the reader has to
     * be able to see that from the card instead of trusting the percentage alone.
     */
    public record NormHighlights(
            LocalDate from,
            LocalDate to,
            List<NormRow> best,
            List<NormRow> worst
    ) {}

    public record NormRow(
            Long productId,
            String productName,
            Long operationId,
            String operationName,
            BigDecimal avgPerformancePct,
            Long sumQuantity,
            Long sumDurationMin
    ) {}
}
