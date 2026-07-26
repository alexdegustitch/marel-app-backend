package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

// Page 2 — Proizvod-datum-operacija-radnik. Backend always pre-aggregates to this exact
// grain (date, shift, product, operation, employee) — the frontend never sees raw work_log
// rows, so response size is bounded by distinct combinations, not by work_log volume.
// The frontend then visually groups these already-small rows into a date -> shift ->
// product -> operation tree (employee is the leaf row). sum* fields are hidden raw
// components needed to correctly recompute a duration-weighted subtotal at EVERY grouping
// level (not just one, unlike page 1/4's single-level grouping).
@Data
@AllArgsConstructor
public class ProductDateOperationEmployeeDto {
    private LocalDate workDate;
    private Long shiftTypeId;
    private String shiftCode;
    private Long productId;
    private String productName;
    private Long operationId;
    private String operationName;
    private Long employeeId;
    private String employeeName;

    private Long sumQuantity;
    private Long sumScrap;
    private Long sumDurationMin;

    private BigDecimal avgPerHour;
    private BigDecimal defectPct;
    private BigDecimal avgPerformancePct;

    private BigDecimal sumWeightedPerformance;
    private Long sumPerformanceDurationMin;
}
