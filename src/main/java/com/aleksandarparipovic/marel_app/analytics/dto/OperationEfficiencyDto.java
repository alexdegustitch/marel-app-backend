package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

// Page 5 — Efikasnost operacija - količina. Flat, grouped by operation only.
@Data
@AllArgsConstructor
public class OperationEfficiencyDto {
    private Long operationId;
    private String operationName;
    private BigDecimal avgPerformancePct;
    private BigDecimal defectPct;
    private BigDecimal avgPerHour;
    private Long sumQuantity;
    private Long sumScrap;
}
