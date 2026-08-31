package com.aleksandarparipovic.marel_app.payroll_run_item_category.dto;

import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
public class PayrollRunItemCategoryResponse {
    private final Long id;
    private final Long payrollRunItemId;
    private final Long workCodeCategoryId;
    private final String sourceType;
    private final Integer totalMinutes;
    private final Integer totalPaidMinutes;
    private final Integer totalQuantity;
    private final Integer totalScrap;
    private final BigDecimal weightedNormMinutes;
    private final BigDecimal performanceCoefficient;
    private final BigDecimal categoryCoefficientSnapshot;
    /** The category's own coefficient; what the payslip prints. */
    private final BigDecimal categoryDefaultCoefficientSnapshot;
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
    private final OffsetDateTime archivedAt;

    public PayrollRunItemCategoryResponse(PayrollRunItemCategory cat) {
        this.id = cat.getId();
        this.payrollRunItemId = cat.getPayrollRunItem() != null ? cat.getPayrollRunItem().getId() : null;
        this.workCodeCategoryId = cat.getWorkCodeCategory() != null ? cat.getWorkCodeCategory().getId() : null;
        this.sourceType = cat.getSourceType();
        this.totalMinutes = cat.getTotalMinutes();
        this.totalPaidMinutes = cat.getTotalPaidMinutes();
        this.totalQuantity = cat.getTotalQuantity();
        this.totalScrap = cat.getTotalScrap();
        this.weightedNormMinutes = cat.getWeightedNormMinutes();
        this.performanceCoefficient = cat.getPerformanceCoefficient();
        this.categoryCoefficientSnapshot = cat.getCategoryCoefficientSnapshot();
        this.categoryDefaultCoefficientSnapshot = cat.getCategoryDefaultCoefficientSnapshot();
        this.effectiveMinutes = cat.getEffectiveMinutes();
        this.hourlyRate = cat.getHourlyRate();
        this.amount = cat.getAmount();
        this.categoryIsPaidSnapshot = cat.getCategoryIsPaidSnapshot();
        this.categoryAffectsNormSnapshot = cat.getCategoryAffectsNormSnapshot();
        this.categoryAffectsBonusSnapshot = cat.getCategoryAffectsBonusSnapshot();
        this.bonusAmount = cat.getBonusAmount();
        this.note = cat.getNote();
        this.createdAt = cat.getCreatedAt();
        this.updatedAt = cat.getUpdatedAt();
        this.archivedAt = cat.getArchivedAt();
    }
}
