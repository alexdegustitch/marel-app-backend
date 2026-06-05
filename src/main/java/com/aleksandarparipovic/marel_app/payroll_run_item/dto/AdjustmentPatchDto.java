package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class AdjustmentPatchDto {

    /** ID of the PayrollAdjustment to update. Required. */
    private Long id;

    /** Override quantity — null means no change. */
    private BigDecimal quantity;

    /** Override unit amount — null means no change. */
    private BigDecimal unitAmount;

    /** Override final amount — null means no change. */
    private BigDecimal amount;

    /** Whether this adjustment is applied — null means no change. */
    private Boolean isApplied;

    /** Free-text note — null means no change. */
    private String note;
}

