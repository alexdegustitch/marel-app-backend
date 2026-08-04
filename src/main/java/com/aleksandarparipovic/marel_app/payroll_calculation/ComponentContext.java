package com.aleksandarparipovic.marel_app.payroll_calculation;

import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Everything a calculator is allowed to see.
 *
 * <p>Deliberately narrow. A calculator gets values, not services: it cannot reach
 * for {@code employee.isForeigner()}, cannot read a setting at {@code now()}, and
 * cannot issue a query of its own. Whatever it needs has been resolved for the
 * payroll period before it is called, which is also what makes a payroll run able
 * to resolve those things once for everybody.
 *
 * @param employeeValues per-employee values in force on {@link #periodStart},
 *                       keyed by {@code EmployeePayrollValueCodes}. A missing key
 *                       means "not configured" — never zero.
 * @param settings       payroll-relevant {@code app_settings} in force on
 *                       {@link #periodStart}, keyed by setting key.
 */
public record ComponentContext(
        PayrollRunItem item,
        MonthlyReport monthlyReport,
        LocalDate periodStart,
        LocalDate periodEnd,
        Map<String, BigDecimal> employeeValues,
        Map<String, BigDecimal> settings
) {
    public Long employeeId() {
        return item.getEmployee() == null ? null : item.getEmployee().getId();
    }
}
