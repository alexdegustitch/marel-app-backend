package com.aleksandarparipovic.marel_app.dashboard.dto;

import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.AttentionOrderRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.CompletedRequestRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.DeliveredOrderRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.Kpis;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.ProgressOverview;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the direktor's control board shows, in one answer.
 *
 * <p>The direktor reads BOTH sides of the factory, so this board is composed
 * from the two that already exist rather than invented: the commercial half
 * (rokovi, isporuke, popunjenost, odrađeni zahtevi) and the supervisor half
 * (ko je odsutan, neradni dani, analitika "obratiti pažnju"). What it does NOT
 * carry is deliberate: no "moji nalozi" (the direktor writes none), no request
 * queue KPIs and no "gde si stao" (that is the administrator's desk), and no
 * "sumnjivi unosi" (an entry-checking chore) — in its slot sits
 * {@code deadlinePressure}, the one question only this reader asks: which
 * orders are near their rok and still far from done.
 *
 * <p>Same doctrine as the other boards: one fat response, every block capped
 * and carrying its own total. No money anywhere — the payroll block says whose
 * month was handed over and which month, never what it comes to.
 */
public record AdminDashboardResponse(
        /** The server's today. Every "days until/overdue" figure below counts from it. */
        LocalDate today,

        /** How far back "isporučeno nedavno" reaches, in days. */
        int deliveredWindowDays,

        /** How far back "nedavno odrađeni zahtevi" reaches, in days. */
        int requestsWindowDays,

        /**
         * The open book's progress figures. Open means everything except
         * delivered and cancelled — a cancelled order is out of the book.
         */
        Kpis kpis,

        /** Open orders past their rok, most overdue first. Both kinds. */
        Block<AttentionOrderRow> late,

        /** Open orders due within three days, soonest first. Both kinds. */
        Block<AttentionOrderRow> dueSoon,

        /** Delivered / closed within the window, newest first. Both kinds. */
        Block<DeliveredOrderRow> delivered,

        /** The open production orders ranked by how filled they are. */
        ProgressOverview progress,

        /** Requests answered within the window, newest answer first. Both workflows. */
        Block<CompletedRequestRow> recentRequests,

        Block<NonWorkingDayRow> upcomingNonWorkingDays,

        /** Who is on sick leave or godišnji odmor today. */
        SupervisorDashboardResponse.AbsenceBlock absences,

        /** Accounts waiting for the direktor's yes. */
        Block<RegistrationRequestRow> registrationRequests,

        /**
         * Months the administrator has handed over (status APPROVED) and payroll
         * has not frozen — "obračuni spremni za pregled". Carries enough rows
         * for the drawer, not just the card.
         */
        Block<ReadyPayrollRow> submittedPayrolls,

        /**
         * Open production orders whose rok is breached or near while the work
         * is still far from done — the direktor's own "obratiti pažnju" card,
         * in the slot the supervisor's "sumnjivi unosi" occupies.
         */
        Block<DeadlinePressureRow> deadlinePressure,

        /** The morning's analytics snapshot, shared with the supervisor board. */
        SupervisorDashboardResponse.Insights insights
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

    public record NonWorkingDayRow(
            LocalDate date,
            String dayType,
            String label,
            long daysUntil
    ) {}

    /**
     * An open, scoped production order whose rok is close (or gone) while its
     * progress is still under the pressure threshold. {@code daysLeft} is
     * negative when the rok is already breached.
     */
    public record DeadlinePressureRow(
            Long id,
            String code,
            String name,
            String customerName,
            LocalDate deadlineDate,
            long daysLeft,
            BigDecimal percent,
            long donePieces,
            long requiredPieces
    ) {}
}
