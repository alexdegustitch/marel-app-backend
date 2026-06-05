package com.aleksandarparipovic.marel_app.payroll_adjustment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PayrollAdjustmentCreateRequest {

    @NotNull
    private Long payrollRunItemId;

    @NotNull
    private Long payrollAdjustmentCategoryId;

    private BigDecimal systemQuantity;
    private BigDecimal quantity;
    private BigDecimal systemUnitAmount;
    private BigDecimal unitAmount;
    private BigDecimal systemAmount;

    @NotNull
    private BigDecimal amount;

    private Boolean isOverridden;
    private String note;
    private Boolean isApplied;
}

