package com.aleksandarparipovic.marel_app.employee_record.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Taking a karton back — what it would touch, and the signed instruction to do
 * it. Gathered in one file because the four records only make sense together.
 */
public final class EmployeeRecordArchiveDtos {

    private EmployeeRecordArchiveDtos() {
    }

    /**
     * What withdrawing one karton would touch, asked before the dialog so the
     * person confirms a number rather than a guess.
     *
     * @param shifts          live shifts that would be withdrawn with it
     * @param workLogs        operations entered on those shifts
     * @param absences        absence records on those shifts
     * @param hasMonthlyReport whether a monthly report was already built
     * @param archivedShifts  shifts somebody already withdrew earlier; untouched
     *                        by this, and the reason an otherwise empty karton
     *                        still cannot be deleted
     * @param deletable       nothing was ever entered, so it can be deleted
     *                        outright instead of archived
     * @param requiresPassword the withdrawal removes work, so it must be signed
     * @param blockedReason   why it is refused, or null when it is allowed
     */
    public record ArchivePreview(
            long shifts,
            long workLogs,
            long absences,
            boolean hasMonthlyReport,
            long archivedShifts,
            boolean deletable,
            boolean requiresPassword,
            String blockedReason
    ) {
    }

    /**
     * Withdraw one karton.
     *
     * <p>{@code password} is required only when the karton holds live shifts —
     * then the withdrawal takes a worker's days out of the reckoning and is
     * signed. An empty karton is a mistake being tidied up and needs no
     * signature; {@link ArchivePreview#requiresPassword()} says which case the
     * screen is in before it asks.
     */
    public record ArchiveRequest(
            String password,
            @Size(max = 255, message = "Razlog može imati najviše 255 znakova.") String reason
    ) {
    }

    /**
     * What withdrawing a whole month would touch.
     *
     * @param kartoni  kartoni that would be withdrawn
     * @param shifts   live shifts across them
     * @param blocked  kartoni the month refuses because their payroll is closed;
     *                 any of these refuses the whole request
     * @param archived kartoni of this month that are ALREADY withdrawn. Not part
     *                 of what this would act on — it is there so the month list
     *                 can tell "nobody has a karton yet" from "they were all
     *                 taken back", which look identical and lead opposite ways:
     *                 the first is fixed by creating, the second only by
     *                 restoring, because a withdrawn karton still occupies
     *                 (employee_id, start_date) and creating would make nothing.
     */
    public record MonthArchivePreview(long kartoni, long shifts, long blocked, long archived) {
    }

    /** Withdraw every live karton of one month, signed. */
    public record MonthArchiveRequest(
            @NotNull Integer year,
            @NotNull @Min(1) @Max(12) Integer month,
            @NotBlank(message = "Lozinka je obavezna.") String password,
            @Size(max = 255, message = "Razlog može imati najviše 255 znakova.") String reason
    ) {
    }

    /** What a karton or month withdrawal actually touched. */
    public record ArchiveSummary(long kartoni, long shifts, LocalDate month) {
    }
}
