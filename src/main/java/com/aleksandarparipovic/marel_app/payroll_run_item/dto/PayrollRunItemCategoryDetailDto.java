package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
public class PayrollRunItemCategoryDetailDto {

    private final Long id;
    private final Long workCodeCategoryId;
    private final String workCodeCategoryNo;
    /** The master (default-locale) name. Unchanged, so nothing that read it breaks. */
    private final String workCodeCategoryName;
    /**
     * The name to display, in the requested locale, falling back to
     * {@link #workCodeCategoryName} when no translation exists.
     *
     * <p>Resolved through the master category — the transactional
     * {@code payroll_run_item_categories} row stores no name of its own, so a
     * corrected translation is picked up everywhere at once instead of leaving
     * thousands of stale copies behind.
     */
    private final String workCodeCategoryDisplayName;
    private final String workCodeCategoryType;
    private final String sourceType;
    private final Integer totalMinutes;
    private final Integer totalPaidMinutes;
    private final Integer totalQuantity;
    private final Integer totalScrap;
    private final BigDecimal weightedNormMinutes;
    private final BigDecimal performanceCoefficient;
    private final BigDecimal categoryCoefficientSnapshot;
    private final BigDecimal effectiveMinutes;
    private final BigDecimal hourlyRate;
    private final BigDecimal amount;
    private final Boolean categoryIsPaidSnapshot;
    private final Boolean categoryAffectsNormSnapshot;
    private final Boolean categoryAffectsBonusSnapshot;
    private final BigDecimal bonusAmount;
    private final String note;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    /** Default locale: the display name is the master name. */
    public PayrollRunItemCategoryDetailDto(PayrollRunItemCategory c) {
        this(c, java.util.Map.of());
    }

    /**
     * @param translations category id -> translated name for one locale, loaded
     *                     once by the caller. Passing the map rather than a
     *                     resolver keeps this DTO free of a per-row query inside
     *                     a payslip loop.
     */
    public PayrollRunItemCategoryDetailDto(PayrollRunItemCategory c, java.util.Map<Long, String> translations) {
        this.id = c.getId();
        this.workCodeCategoryId = c.getWorkCodeCategory().getId();
        this.workCodeCategoryNo = c.getWorkCodeCategory().getCategoryNo();
        this.workCodeCategoryName = c.getWorkCodeCategory().getCategoryName();
        String translated = translations == null ? null : translations.get(this.workCodeCategoryId);
        this.workCodeCategoryDisplayName =
                translated != null && !translated.isBlank() ? translated : this.workCodeCategoryName;
        this.workCodeCategoryType = c.getWorkCodeCategory().getType();
        this.sourceType = c.getSourceType();
        this.totalMinutes = c.getTotalMinutes();
        this.totalPaidMinutes = c.getTotalPaidMinutes();
        this.totalQuantity = c.getTotalQuantity();
        this.totalScrap = c.getTotalScrap();
        this.weightedNormMinutes = c.getWeightedNormMinutes();
        this.performanceCoefficient = c.getPerformanceCoefficient();
        this.categoryCoefficientSnapshot = c.getCategoryCoefficientSnapshot();
        this.effectiveMinutes = c.getEffectiveMinutes();
        this.hourlyRate = c.getHourlyRate();
        this.amount = c.getAmount();
        this.categoryIsPaidSnapshot = c.getCategoryIsPaidSnapshot();
        this.categoryAffectsNormSnapshot = c.getCategoryAffectsNormSnapshot();
        this.categoryAffectsBonusSnapshot = c.getCategoryAffectsBonusSnapshot();
        this.bonusAmount = c.getBonusAmount();
        this.note = c.getNote();
        this.createdAt = c.getCreatedAt();
        this.updatedAt = c.getUpdatedAt();
    }
}

