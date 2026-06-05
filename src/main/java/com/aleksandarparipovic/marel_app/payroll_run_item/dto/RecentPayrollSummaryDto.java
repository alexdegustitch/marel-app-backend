package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecentPayrollSummaryDto(
        Long id,
        Long monthlyReportId,
        LocalDate period,
        String status,
        BigDecimal totalNetEarnings,
        BigDecimal netPayableAmount
) {
    public RecentPayrollSummaryDto(PayrollRunItem item) {
        this(
                item.getId(),
                item.getMonthlyReport() != null ? item.getMonthlyReport().getId() : null,
                item.getPeriod(),
                item.getStatus(),
                item.getTotalNetEarnings(),
                item.getNetPayableAmount()
        );
    }
}

