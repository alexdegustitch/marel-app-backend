package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Getter
@NoArgsConstructor
public class PayrollRunItemPatchRequest {

    // ── Manually editable PayrollRunItem fields ──────────────────────────────

    /** Manual minute adjustment (positive or negative). */
    private Integer manualAdjustedMinutes;

    private String note;

    /** Override value; null = no change. Setting equal to system value resets the override flag. */
    private BigDecimal hourlyRate;

    private BigDecimal mealAllowanceUnitAmount;

    private BigDecimal totalTransportAllowanceAmount;

    private BigDecimal baseBonusAmount;

    private BigDecimal bonusCorrectionAmount;

    private BigDecimal totalBonusAmount;

    // ── Adjustment overrides ─────────────────────────────────────────────────

    /**
     * List of adjustments to patch. Only adjustments included here are touched;
     * omitting an adjustment leaves it unchanged.
     */
    private List<AdjustmentPatchDto> adjustments;
}

