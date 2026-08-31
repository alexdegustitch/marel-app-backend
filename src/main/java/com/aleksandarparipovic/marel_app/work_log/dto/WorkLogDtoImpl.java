package com.aleksandarparipovic.marel_app.work_log.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class WorkLogDtoImpl implements WorkLogDto {
    private final Long id;
    private final Long shiftId;
    private final Long operationId;
    private final String operationName;
    private final Integer minNorm;
    private final Long productionOrderId;
    private final String productionOrderName;
    private final String productionOrderCode;
    private final Long productId;
    private final String productName;
    private final java.math.BigDecimal performanceRate;
    private final java.math.BigDecimal approvedPerformanceRate;
    private final Instant startAt;
    private final Instant endAt;
    private final Integer durationMin;
    private final Integer quantity;
    private final Integer scrap;
    private final String note;
    private final java.math.BigDecimal hourlyOutput;
    private final Long workCodeCategoryId;
    private final String workCodeCategoryNo;
    private final Long effectiveWorkCodeCategoryId;
    private final String effectiveWorkCodeCategoryNo;
    private final Boolean isActive;
    private final java.math.BigDecimal normMultiplierSnapshot;
    private final java.math.BigDecimal normMultiplierManual;
    private final Boolean allowsParallelWork;
}