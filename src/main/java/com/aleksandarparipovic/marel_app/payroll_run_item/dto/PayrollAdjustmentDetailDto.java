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
    /** The master (default-locale) name. Unchanged. */
    private final String categoryName;
    /**
     * The name to display, in the requested locale, falling back to
     * {@link #categoryName}.
     *
     * <p>Always resolved through payroll_adjustment_category_id. The adjustment
     * row itself stores no name, translated or otherwise.
     */
    private final String categoryDisplayName;
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

    /** Default locale: the display name is the master name. */
    public PayrollAdjustmentDetailDto(PayrollAdjustment a) {
        this(a, java.util.Map.of());
    }

    /**
     * @param translations category id -> translated name for one locale, loaded
     *                     once by the caller.
     */
    public PayrollAdjustmentDetailDto(PayrollAdjustment a, java.util.Map<Long, String> translations) {
        PayrollAdjustmentCategory cat = a.getPayrollAdjustmentCategory();

        this.id = a.getId();
        this.payrollAdjustmentCategoryId = cat.getId();

        this.categoryCode = cat.getCode();
        this.categoryName = cat.getName();
        String translated = translations == null ? null : translations.get(cat.getId());
        this.categoryDisplayName =
                translated != null && !translated.isBlank() ? translated : this.categoryName;
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
