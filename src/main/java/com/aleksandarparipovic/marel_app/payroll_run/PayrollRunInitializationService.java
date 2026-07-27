package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_run.event.PayrollMonthInitEvent;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScope;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScopeService;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

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
    private final PayrollSchemeScopeService payrollSchemeScopeService;

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
            List<PayrollAdjustmentCategory> allAdjCategoriesForScope =
                    payrollAdjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();

            // What each employee's compensation scheme allows across this month.
            // Resolved once for the whole run, not per employee.
            Map<Long, PayrollSchemeScope> scopes = payrollSchemeScopeService.scopesFor(
                    items.stream().map(i -> i.getEmployee().getId()).toList(),
                    YearMonth.of(year, month).atDay(1),
                    YearMonth.of(year, month).atEndOfMonth(),
                    activeWorkCategories,
                    allAdjCategoriesForScope);

            txService.createPayrollRunItemCategories(items, activeWorkCategories, scopes);

            // Phase 5: create adjustments for each active adjustment category
            List<PayrollAdjustmentCategory> activeAdjCategories =
                    payrollAdjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();
            txService.createPayrollAdjustments(items, activeAdjCategories, scopes, userId);

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
        List<PayrollAdjustmentCategory> activeAdjCategories = payrollAdjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();

        Map<Long, PayrollSchemeScope> scopes = payrollSchemeScopeService.scopesFor(
                items.stream().map(i -> i.getEmployee().getId()).toList(),
                mr.getStartDate(),
                mr.getEndDate(),
                activeWorkCategories,
                activeAdjCategories);

        txService.createPayrollRunItemCategories(items, activeWorkCategories, scopes);
        txService.createPayrollAdjustments(items, activeAdjCategories, scopes, userId);

        txService.populatePreviousMonthData(items);

        log.info("[PayrollInit] Initialized payroll structures for monthlyReport id={}, payrollRunId={}, items={}",
                mr.getId(), payrollRun.getId(), items.size());
    }
}

