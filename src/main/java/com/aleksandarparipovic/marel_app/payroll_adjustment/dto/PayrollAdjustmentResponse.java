package com.aleksandarparipovic.marel_app.payroll_adjustment.dto;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
public class PayrollAdjustmentResponse {

    private final Long id;
    private final Long payrollRunItemId;
    private final Long payrollAdjustmentCategoryId;

    // ── Category snapshot (denormalized for convenience) ─────────────────────
    private final String categoryCode;
    private final String categoryName;
    private final String impactCode;
    private final String inputType;
    private final Boolean isManual;
    private final Boolean allowOverride;
    private final String overrideTarget;

    // ── Values ───────────────────────────────────────────────────────────────
    private final BigDecimal systemQuantity;
    private final BigDecimal quantity;
    private final BigDecimal systemUnitAmount;
    private final BigDecimal unitAmount;
    private final BigDecimal systemAmount;
    private final BigDecimal amount;
    private final Boolean isOverridden;
    private final String note;
    private final Boolean isApplied;

    private final Long createdById;
    private final Long editedById;
    private final OffsetDateTime editedAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    public PayrollAdjustmentResponse(PayrollAdjustment a) {
        this.id = a.getId();
        this.payrollRunItemId = a.getPayrollRunItem() != null ? a.getPayrollRunItem().getId() : null;
        this.payrollAdjustmentCategoryId = a.getPayrollAdjustmentCategory() != null ? a.getPayrollAdjustmentCategory().getId() : null;

        var cat = a.getPayrollAdjustmentCategory();
        this.categoryCode = cat != null ? cat.getCode() : null;
        this.categoryName = cat != null ? cat.getName() : null;
        this.impactCode = cat != null ? cat.getImpactCode() : null;
        this.inputType = cat != null ? cat.getInputType() : null;
        this.isManual = cat != null ? cat.getIsManual() : null;
        this.allowOverride = cat != null ? cat.getAllowOverride() : null;
        this.overrideTarget = cat != null ? cat.getOverrideTarget() : null;

        this.systemQuantity = a.getSystemQuantity();
        this.quantity = a.getQuantity();
        this.systemUnitAmount = a.getSystemUnitAmount();
        this.unitAmount = a.getUnitAmount();
        this.systemAmount = a.getSystemAmount();
        this.amount = a.getAmount();
        this.isOverridden = a.getIsOverridden();
        this.note = a.getNote();
        this.isApplied = a.getIsApplied();

        this.createdById = a.getCreatedBy() != null ? a.getCreatedBy().getId() : null;
        this.editedById = a.getEditedBy() != null ? a.getEditedBy().getId() : null;
        this.editedAt = a.getEditedAt();
        this.createdAt = a.getCreatedAt();
        this.updatedAt = a.getUpdatedAt();
        this.archivedAt = a.getArchivedAt();
    }
}

