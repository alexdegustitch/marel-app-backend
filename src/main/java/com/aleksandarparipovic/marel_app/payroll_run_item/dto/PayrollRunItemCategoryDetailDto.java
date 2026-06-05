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
    private final String workCodeCategoryName;
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

    public PayrollRunItemCategoryDetailDto(PayrollRunItemCategory c) {
        this.id = c.getId();
        this.workCodeCategoryId = c.getWorkCodeCategory().getId();
        this.workCodeCategoryNo = c.getWorkCodeCategory().getCategoryNo();
        this.workCodeCategoryName = c.getWorkCodeCategory().getCategoryName();
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

