package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
public class PayrollAdjustmentDetailDto {

    private final Long id;
    private final Long payrollAdjustmentCategoryId;

    // ── Category definition ──────────────────────────────────────────────────
    private final String categoryCode;
    private final String categoryName;
    private final String sectionCode;
    private final Integer sectionOrder;
    private final Integer sortOrder;
    private final String impactCode;
    private final String inputType;
    private final Boolean isManual;
    private final Boolean allowOverride;
    private final String overrideTarget;
    private final Boolean allowNegative;
    private final Boolean visibleInUi;
    private final Boolean visibleInPdf;
    private final Boolean showName;

    // ── Adjustment values ────────────────────────────────────────────────────
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

    public PayrollAdjustmentDetailDto(PayrollAdjustment a) {
        PayrollAdjustmentCategory cat = a.getPayrollAdjustmentCategory();

        this.id = a.getId();
        this.payrollAdjustmentCategoryId = cat.getId();

        this.categoryCode = cat.getCode();
        this.categoryName = cat.getName();
        this.sectionCode = cat.getSectionCode();
        this.sectionOrder = cat.getSectionOrder();
        this.sortOrder = cat.getSortOrder();
        this.impactCode = cat.getImpactCode();
        this.inputType = cat.getInputType();
        this.isManual = cat.getIsManual();
        this.allowOverride = cat.getAllowOverride();
        this.overrideTarget = cat.getOverrideTarget();
        this.allowNegative = cat.getAllowNegative();
        this.visibleInUi = cat.getVisibleInUi();
        this.visibleInPdf = cat.getVisibleInPdf();
        this.showName = cat.getShowName();

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
    }
}
