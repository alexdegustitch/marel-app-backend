package com.aleksandarparipovic.marel_app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

// Page 3 — Efikasnost radnika. Flat, grouped by employee only.
@Data
@AllArgsConstructor
public class EmployeeEfficiencyDto {
    private Long employeeId;
    private String employeeName;
    private BigDecimal avgPerformancePct;
    private BigDecimal defectPct;
    private Long sumQuantity;
    private Long sumScrap;
}
