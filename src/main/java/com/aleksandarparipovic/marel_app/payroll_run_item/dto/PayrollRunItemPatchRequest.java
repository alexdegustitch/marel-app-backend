package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
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
    private BigDecimal currentMonthTelephone;

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

    /** Plain setter: null means "no change", so no presence flag is needed. */
    public void setCurrentMonthTelephone(BigDecimal currentMonthTelephone) {
        this.currentMonthTelephone = currentMonthTelephone;
    }

    // ── JsonSetters to mark presence ─────────────────────────────────────────

    // mealAllowanceUnitAmount and totalTransportAllowanceAmount are gone: those
    // two are edited on their lines, through the `adjustments` array, which is
    // where the calculation reads them from.




    @JsonSetter(value = "hourlyRate", nulls = Nulls.AS_EMPTY)
    public void setHourlyRate(BigDecimal v) {
        this.hourlyRate = v;
        this.hourlyRatePresent = true;
    }
}
