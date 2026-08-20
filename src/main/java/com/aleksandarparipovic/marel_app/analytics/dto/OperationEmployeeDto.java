package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Page 5 — Efikasnost operacija, at (operation, worker) grain.
 *
 * <p>An operation belongs to exactly one product, so the product is carried as CONTEXT rather
 * than as a level of its own: operation names repeat across products, and "Brušenje" on its
 * own does not say which one is being read about.
 *
 * <p>The frontend builds an operation → worker tree out of these rows, and the operation band
 * recomputes its totals from the hidden sum* components — averaging the workers' own
 * percentages would weigh ten minutes of work as heavily as a whole shift.
 */
@Data
@AllArgsConstructor
public class OperationEmployeeDto {
    private Long operationId;
    private String operationName;
    private Long productId;
    private String productName;
    private Long employeeId;
    private String employeeName;

    private Long sumQuantity;
    private Long sumScrap;
    private Long sumDurationMin;

    private BigDecimal avgPerformancePct;
    private BigDecimal defectPct;

    private BigDecimal sumWeightedPerformance;
    private Long sumPerformanceDurationMin;
}
