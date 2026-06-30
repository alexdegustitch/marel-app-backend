package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_run.event.PayrollMonthInitEvent;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles async bulk initialization of all payroll structures for a given month.
 * Triggered after employee records are committed via {@link PayrollMonthInitEvent}.
 *
 * <p>Each phase runs in its own transaction to keep individual steps small and
 * avoid one giant transaction that holds locks for too long.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollRunInitializationService {
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollAdjustmentCategoryRepository payrollAdjustmentCategoryRepository;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final PayrollRunInitializationTxService txService;

    /**
     * Entry point — called from the event listener after HTTP transaction commits.
     */
    @Async
    public void initializePayrollMonth(PayrollMonthInitEvent event) {
        int year = event.year();
        int month = event.month();
        Long userId = event.initiatedByUserId();
        List<Long> employeeRecordIds = event.employeeRecordIds();

        log.info("[PayrollInit] Starting async initialization for {}/{}, {} employee records, userId={}",
                year, month, employeeRecordIds.size(), userId);

        try {
            // Phase 1: create monthly reports for each employee record
            txService.createMonthlyReports(employeeRecordIds, year, month);

            // Phase 2: create or get the single payroll run for this month
            PayrollRun payrollRun = txService.getOrCreatePayrollRun(year, month, userId);

            // Phase 3: create payroll run items — re-fetch with employee eagerly loaded
            List<MonthlyReport> monthlyReportsWithEmployee =
                    txService.loadMonthlyReportsWithEmployee(employeeRecordIds);
            List<PayrollRunItem> items = txService.createPayrollRunItems(payrollRun, monthlyReportsWithEmployee);

            // Phase 4: create item categories for each active work code category
            List<WorkCodeCategory> activeWorkCategories = workCodeCategoryRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByDisplayOrderAscIdAsc();
            txService.createPayrollRunItemCategories(items, activeWorkCategories);

            // Phase 5: create adjustments for each active adjustment category
            List<PayrollAdjustmentCategory> activeAdjCategories =
                    payrollAdjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();
            txService.createPayrollAdjustments(items, activeAdjCategories, userId);

            txService.populatePreviousMonthData(items);

            log.info("[PayrollInit] Completed initialization for {}/{}: payrollRunId={}, items={}, monthlyReports={}",
                    year, month, payrollRun.getId(), items.size(), monthlyReportsWithEmployee.size());

        } catch (Exception ex) {
            log.error("[PayrollInit] Failed initialization for {}/{}: {}", year, month, ex.getMessage(), ex);
        }
    }

    // ─── Single monthly-report initialization ────────────────────────────────

    /**
     * Called after a single {@link MonthlyReport} is created to ensure the matching
     * payroll structures exist. If no PayrollRun exists yet for the period, exits silently.
     */
    public void initializePayrollForMonthlyReport(MonthlyReport mr, Long userId) {
        int year  = mr.getStartDate().getYear();
        int month = mr.getStartDate().getMonthValue();

        PayrollRun payrollRun = payrollRunRepository
                .findFirstByReportYearAndReportMonth(year, month)
                .orElse(null);

        if (payrollRun == null) {
            log.info("[PayrollInit] No PayrollRun found for {}/{} — skipping payroll init for monthlyReport id={}",
                    year, month, mr.getId());
            return;
        }

        List<MonthlyReport> single = List.of(mr);
        List<PayrollRunItem> items = txService.createPayrollRunItems(payrollRun, single);

        List<WorkCodeCategory> activeWorkCategories = workCodeCategoryRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByDisplayOrderAscIdAsc();
        txService.createPayrollRunItemCategories(items, activeWorkCategories);

        List<PayrollAdjustmentCategory> activeAdjCategories = payrollAdjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();
        txService.createPayrollAdjustments(items, activeAdjCategories, userId);

        txService.populatePreviousMonthData(items);

        log.info("[PayrollInit] Initialized payroll structures for monthlyReport id={}, payrollRunId={}, items={}",
                mr.getId(), payrollRun.getId(), items.size());
    }
}

