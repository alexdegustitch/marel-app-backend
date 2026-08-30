package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Getter
@NoArgsConstructor
public class PayrollRunItemPatchRequest {

    // ── Simple fields (null = no change) ────────────────────────────────────

    private Integer manualAdjustedMinutes;
    /**
     * Why the working time was corrected. Compulsory when the correction changes
     * — a change to somebody's paid time that says nothing about why is what
     * payroll_time_adjustments exists to stop. Ignored when the minutes are
     * unchanged, so re-saving a form does not demand the reason again.
     */
    private String manualAdjustedMinutesReason;
    private String note;
    private BigDecimal totalNetEarnings;

    /**
     * The director's note for this month's payslip.
     *
     * <p>Carries a presence flag for the same reason the hourly rate does: an
     * explicit {@code null} means CLEAR IT, and an absent field means leave it
     * alone. Without the flag the two are the same value and the note could
     * never be emptied.
     */
    private String directorNote;
    private boolean directorNotePresent;

    // ── Overridable fields (null = reset to system value) ────────────────────
    // Each field has a companion "present" flag so the service can distinguish
    // "field absent from JSON" (flag=false) from "field explicitly sent as null" (flag=true, value=null).






    private BigDecimal hourlyRate;
    private boolean hourlyRatePresent;

    // ── Adjustment overrides ─────────────────────────────────────────────────

    private List<AdjustmentPatchDto> adjustments;

    /**
     * Plain setter: this field has no "present" flag because an absent list and an
     * empty one mean the same thing here — no adjustment lines were patched.
     */
    public void setAdjustments(List<AdjustmentPatchDto> adjustments) {
        this.adjustments = adjustments;
    }

    // ── JsonSetters to mark presence ─────────────────────────────────────────

    // mealAllowanceUnitAmount, totalTransportAllowanceAmount, the three bonus
    // fields and currentMonthTelephone are all gone: every one of them is edited
    // on its line, through the `adjustments` array, which is where the
    // calculation reads them from.




    /**
     * An explicit {@code null} means RESET — take the system rate again.
     *
     * <p>Which is why {@code Nulls.AS_EMPTY} is not used here, and was the bug:
     * for a BigDecimal, Jackson's "empty" value is ZERO, so a reset arrived as a
     * typed-in 0. The payroll then recorded an hourly rate of zero and marked it
     * OVERRIDDEN — the opposite of what the button says — and the employee's real
     * rate was left sitting unused in hourly_rate_system.
     *
     * <p>The presence flag is what distinguishes this from a field that was never
     * sent, so null can keep its own meaning here instead of being coerced into a
     * number that means something else.
     */
    @JsonSetter("hourlyRate")
    public void setHourlyRate(BigDecimal v) {
        this.hourlyRate = v;
        this.hourlyRatePresent = true;
    }

    /** An explicit {@code null} empties the note; an absent field leaves it. */
    @JsonSetter("directorNote")
    public void setDirectorNote(String v) {
        this.directorNote = v;
        this.directorNotePresent = true;
    }
}
