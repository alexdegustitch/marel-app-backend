package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Page 3 — Efikasnost radnika, at (worker, product, operation) grain.
 *
 * <p>Pre-aggregated to that grain by the server, so what travels is bounded by distinct
 * combinations rather than by how much work was recorded. The frontend builds a worker →
 * product → operation tree out of it, and every band recomputes its own totals from the
 * hidden sum* components below — averaging the percentages beneath a band would weigh ten
 * minutes of work as heavily as a whole shift.
 */
@Data
@AllArgsConstructor
public class EmployeeProductOperationDto {
    private Long employeeId;
    private String employeeName;
    private Long productId;
    private String productName;
    private Long operationId;
    private String operationName;

    private Long sumQuantity;
    private Long sumScrap;
    private Long sumDurationMin;

    private BigDecimal avgPerformancePct;
    private BigDecimal defectPct;

    private BigDecimal sumWeightedPerformance;
    private Long sumPerformanceDurationMin;
}
