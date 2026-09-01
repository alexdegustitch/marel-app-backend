package com.aleksandarparipovic.marel_app.absence_record;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Tells the payroll that a month's absences moved.
 *
 * <p><b>Why the payroll has to be told at all.</b> An absence is priced: its
 * minutes reach a payslip as a line of NO, through
 * {@code daily_report_categories → monthly_report_categories →
 * payroll_run_item_categories}. A payroll item re-prices when it notices it is
 * stale, and left to itself it notices only when the monthly report's version
 * moves — which is a race nobody should have to reason about for a figure
 * somebody is going to be paid against.
 *
 * <p>Flagged rather than recalculated, exactly as
 * {@code AffectedMonthsRecalculator} does it: the item re-prices on the next
 * read, which every payroll list already performs, and recalculating inside the
 * caller's transaction would make recording one absence wait on a whole month's
 * arithmetic.
 *
 * <p>LOCKED items are left alone by the query itself. A locked figure is one
 * somebody signed off; marking it stale would either quietly rewrite an approved
 * number or leave a flag nothing will ever clear.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbsencePayrollNotice {

    private final PayrollRunItemRepository payrollRunItemRepository;

    /** @param workDate the shift's date — the month it names is the one repriced */
    public void monthNeedsRepricing(Long employeeId, LocalDate workDate) {
        if (employeeId == null || workDate == null) {
            return;
        }
        int marked = payrollRunItemRepository.markNeedsRecalculationByEmployeeAndMonth(
                employeeId, workDate.getYear(), workDate.getMonthValue());
        if (marked > 0) {
            log.debug("Employee {}: {} payroll item(s) for {}/{} flagged after an absence changed",
                    employeeId, marked, workDate.getMonthValue(), workDate.getYear());
        }
    }
}
