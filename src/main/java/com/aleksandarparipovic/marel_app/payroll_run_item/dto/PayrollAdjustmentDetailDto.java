package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
public class PayrollAdjustmentDetailDto {

    private final Long id;
    private final Long payrollAdjustmentCategoryId;
    private final String categoryCode;
    private final String categoryName;
    private final String categoryType;
    private final String amountType;
    private final Boolean affectsGross;
    private final Boolean affectsNet;
    private final BigDecimal amount;
    private final String reason;
    private final Boolean isApplied;
    private final Long createdById;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public PayrollAdjustmentDetailDto(PayrollAdjustment a) {
        this.id = a.getId();
        this.payrollAdjustmentCategoryId = a.getPayrollAdjustmentCategory().getId();
        this.categoryCode = a.getPayrollAdjustmentCategory().getCategoryCode();
        this.categoryName = a.getPayrollAdjustmentCategory().getCategoryName();
        this.categoryType = a.getPayrollAdjustmentCategory().getCategoryType();
        this.amountType = a.getPayrollAdjustmentCategory().getAmountType();
        this.affectsGross = a.getPayrollAdjustmentCategory().getAffectsGross();
        this.affectsNet = a.getPayrollAdjustmentCategory().getAffectsNet();
        this.amount = a.getAmount();
        this.reason = a.getReason();
        this.isApplied = a.getIsApplied();
        this.createdById = a.getCreatedBy() != null ? a.getCreatedBy().getId() : null;
        this.createdAt = a.getCreatedAt();
        this.updatedAt = a.getUpdatedAt();
    }
}

