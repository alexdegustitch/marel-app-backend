package com.aleksandarparipovic.marel_app.employee_leave.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The period-entry contract, in one place: what is asked, what the plan for
 * each day is, and what the apply did. One request record serves both preview
 * and apply — the preview simply ignores the two confirmation flags — so the
 * two calls cannot drift apart in shape.
 */
public final class EmployeeLeaveDtos {

    private EmployeeLeaveDtos() {
    }

    /** One od–do entry: preview it first, then apply the same body. */
    public record LeaveRequest(
            @NotNull Long employeeId,
            @NotNull Long workCodeCategoryId,
            @NotNull LocalDate dateFrom,
            @NotNull LocalDate dateTo,
            String note,
            /** Consent to archive the conflicting shifts the preview named. */
            boolean archiveConflicts,
            /** Consent to EXTENDED sick leave without thirty days of history. */
            boolean acceptExtendedWarning,
            /**
             * Count a plain Saturday as a working day, so the leave is written on
             * it. The board's quick single-day entry sends true — the factory
             * normally works Saturdays, and that drawer lists them as missing —
             * while the calendar's period entry keeps the default of skipping
             * weekends whole. Sundays and holidays are skipped either way.
             */
            boolean includeSaturdays
    ) {
    }

    /**
     * What happens to one day of the period.
     *
     * <p>{@code status}: CREATE | SKIP_WEEKEND | SKIP_NON_WORKING |
     * SKIP_SAME_TYPE | CONFLICT | CLOSED_MONTH.
     */
    public record LeaveDayPlan(
            LocalDate date,
            String status,
            /** Auto-upgraded to the EXTENDED category by the thirty-day rule. */
            boolean extended,
            /** Which calendar day of the continuous sick-leave run this is, when tracked. */
            Integer streakDay,
            /** The work calendar's name for the day, when it is a holiday or collective leave. */
            String holidayLabel,
            /** The shifts in the way, when status is CONFLICT. */
            List<ConflictShift> conflicts
    ) {
    }

    /** A shift a leave day would have to archive: enough to name it to the user. */
    public record ConflictShift(Long workShiftId, String shiftName, String categoryNo, String categoryName) {
    }

    /** A month excluded because its payroll is already handed over. */
    public record ClosedMonth(int year, int month, Long payrollRunItemId, String status) {
    }

    public record LeavePreviewResponse(
            List<LeaveDayPlan> days,
            int toCreate,
            int conflictDays,
            /** EXTENDED was chosen without thirty continuous days of sick leave behind it. */
            boolean extendedWithoutHistory,
            /** Name of the category auto-upgraded days would be written as, when the rule can fire. */
            String extendedCategoryName,
            /** The shift template the created days would use. */
            String defaultShiftName,
            List<ClosedMonth> closedMonths
    ) {
    }

    public record LeaveApplyResponse(
            Long leavePeriodId,
            int createdShifts,
            int archivedShifts,
            List<ClosedMonth> skippedClosedMonths
    ) {
    }

    /** One recorded period, as the calendar screen lists them. */
    public record LeavePeriodDto(
            Long id,
            Long employeeId,
            Long workCodeCategoryId,
            String categoryNo,
            String categoryName,
            LocalDate dateFrom,
            LocalDate dateTo,
            String note,
            OffsetDateTime createdAt
    ) {
    }

    /**
     * A day the karton-creation sweep could NOT materialise because a shift of
     * another type stands on it. Reported, never archived silently — archiving
     * needs a person's consent, which the sweep does not have.
     */
    public record MaterializationConflict(
            Long employeeId,
            String employeeName,
            String categoryNo,
            List<LocalDate> dates
    ) {
    }

    /** What one month's sweep did for one employee's periods. */
    public record MonthMaterialization(int createdShifts, List<MaterializationConflict> conflicts) {
    }
}
