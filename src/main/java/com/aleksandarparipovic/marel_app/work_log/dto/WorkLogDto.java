package com.aleksandarparipovic.marel_app.work_log.dto;

import java.math.BigDecimal;
import java.time.Instant;

public interface WorkLogDto {

    Long getId();

    Long getShiftId();

    Long getOperationId();
    String getOperationName();
    Integer getMinNorm();

    Long getProductionOrderId();
    String getProductionOrderName();
    String getProductionOrderCode();

    Long getProductId();
    String getProductName();
    BigDecimal getPerformanceRate();
    BigDecimal getApprovedPerformanceRate();
    Instant getStartAt();
    Instant getEndAt();

    Integer getDurationMin();
    Integer getQuantity();
    Integer getScrap();

    String getNote();

    BigDecimal getHourlyOutput();

    Long getWorkCodeCategoryId();
    String getWorkCodeCategoryNo();

    // Bonus-effective category (e.g. JB) when a night/weekend remap is active; null otherwise.
    Long getEffectiveWorkCodeCategoryId();
    String getEffectiveWorkCodeCategoryNo();

    Boolean getIsActive();

    BigDecimal getNormMultiplierSnapshot();

    Boolean getAllowsParallelWork();
}
