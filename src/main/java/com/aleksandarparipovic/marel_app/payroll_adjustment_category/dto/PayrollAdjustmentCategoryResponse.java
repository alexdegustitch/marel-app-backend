package com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto;

import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
public class PayrollAdjustmentCategoryResponse {

    private final Long id;
    /** Stable identifier. Never translated. */
    private final String code;
    /** The default-locale name, held on the master row. */
    private final String name;
    /**
     * The English translation, or {@code null} when none has been entered.
     *
     * <p>Null rather than a copy of {@link #name}, so an administration screen
     * can tell "no translation yet" from "translated to the same words" and the
     * list of categories still needing one is derivable.
     */
    private final String nameEn;
    private final String sectionCode;
    private final Integer sectionOrder;
    private final Integer sortOrder;
    private final String impactCode;
    private final String inputType;
    private final Boolean isManual;
    private final Boolean allowOverride;
    private final String overrideTarget;
    private final Boolean allowNegative;
    private final Boolean isActive;
    private final Boolean visibleInUi;
    private final Boolean visibleInPdf;
    private final String calculationKey;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    public PayrollAdjustmentCategoryResponse(PayrollAdjustmentCategory c) {
        this(c, null);
    }

    public PayrollAdjustmentCategoryResponse(PayrollAdjustmentCategory c, String nameEn) {
        this.id = c.getId();
        this.code = c.getCode();
        this.name = c.getName();
        this.nameEn = nameEn;
        this.sectionCode = c.getSectionCode();
        this.sectionOrder = c.getSectionOrder();
        this.sortOrder = c.getSortOrder();
        this.impactCode = c.getImpactCode();
        this.inputType = c.getInputType();
        this.isManual = c.getIsManual();
        this.allowOverride = c.getAllowOverride();
        this.overrideTarget = c.getOverrideTarget();
        this.allowNegative = c.getAllowNegative();
        this.isActive = c.getIsActive();
        this.visibleInUi = c.getVisibleInUi();
        this.visibleInPdf = c.getVisibleInPdf();
        this.calculationKey = c.getCalculationKey();
        this.createdAt = c.getCreatedAt();
        this.updatedAt = c.getUpdatedAt();
        this.archivedAt = c.getArchivedAt();
    }
}

