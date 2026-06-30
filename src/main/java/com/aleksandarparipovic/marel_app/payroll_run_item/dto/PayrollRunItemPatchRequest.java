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
    private String note;
    private BigDecimal totalNetEarnings;
    private BigDecimal currentMonthTelephone;

    // ── Overridable fields (null = reset to system value) ────────────────────
    // Each field has a companion "present" flag so the service can distinguish
    // "field absent from JSON" (flag=false) from "field explicitly sent as null" (flag=true, value=null).

    private BigDecimal mealAllowanceUnitAmount;
    private boolean mealAllowanceUnitAmountPresent;

    private BigDecimal totalTransportAllowanceAmount;
    private boolean totalTransportAllowanceAmountPresent;

    private BigDecimal baseBonusAmount;
    private boolean baseBonusAmountPresent;

    private BigDecimal bonusCorrectionAmount;
    private boolean bonusCorrectionAmountPresent;

    private BigDecimal totalBonusAmount;
    private boolean totalBonusAmountPresent;

    private BigDecimal hourlyRate;
    private boolean hourlyRatePresent;

    // ── Adjustment overrides ─────────────────────────────────────────────────

    private List<AdjustmentPatchDto> adjustments;

    // ── JsonSetters to mark presence ─────────────────────────────────────────

    @JsonSetter(value = "mealAllowanceUnitAmount", nulls = Nulls.AS_EMPTY)
    public void setMealAllowanceUnitAmount(BigDecimal v) {
        this.mealAllowanceUnitAmount = v;
        this.mealAllowanceUnitAmountPresent = true;
    }

    @JsonSetter(value = "totalTransportAllowanceAmount", nulls = Nulls.AS_EMPTY)
    public void setTotalTransportAllowanceAmount(BigDecimal v) {
        this.totalTransportAllowanceAmount = v;
        this.totalTransportAllowanceAmountPresent = true;
    }

    @JsonSetter(value = "baseBonusAmount", nulls = Nulls.AS_EMPTY)
    public void setBaseBonusAmount(BigDecimal v) {
        this.baseBonusAmount = v;
        this.baseBonusAmountPresent = true;
    }

    @JsonSetter(value = "bonusCorrectionAmount", nulls = Nulls.AS_EMPTY)
    public void setBonusCorrectionAmount(BigDecimal v) {
        this.bonusCorrectionAmount = v;
        this.bonusCorrectionAmountPresent = true;
    }

    @JsonSetter(value = "totalBonusAmount", nulls = Nulls.AS_EMPTY)
    public void setTotalBonusAmount(BigDecimal v) {
        this.totalBonusAmount = v;
        this.totalBonusAmountPresent = true;
    }

    @JsonSetter(value = "hourlyRate", nulls = Nulls.AS_EMPTY)
    public void setHourlyRate(BigDecimal v) {
        this.hourlyRate = v;
        this.hourlyRatePresent = true;
    }
}
