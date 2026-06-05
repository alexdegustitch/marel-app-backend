package com.aleksandarparipovic.marel_app.summary.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class MonthlySummaryDto {
    private Long employeeId;
    private Integer reportYear;
    private Integer reportMonth;
    private long totalShiftMinutes;
    private long totalWorkMinutes;
    private long totalQuantity;
    private long totalScrap;
    private BigDecimal totalEffectiveMinutes;
    /** True when monthly data may still be stale (daily worker not yet finished). */
    private boolean stale;
}
