package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_run.event.PayrollMonthInitEvent;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    private final EmployeeRecordRepository employeeRecordRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;
    private final PayrollAdjustmentRepository payrollAdjustmentRepository;
    private final PayrollAdjustmentCategoryRepository payrollAdjustmentCategoryRepository;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;

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
            List<MonthlyReport> monthlyReports = createMonthlyReports(employeeRecordIds, year, month);

            // Phase 2: create or get the single payroll run for this month
            PayrollRun payrollRun = getOrCreatePayrollRun(year, month, userId);

            // Phase 3: create payroll run items (one per monthly report / employee)
            List<PayrollRunItem> items = createPayrollRunItems(payrollRun, monthlyReports);

            // Phase 4: create item categories for each active work code category
            List<WorkCodeCategory> activeWorkCategories = workCodeCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();
            createPayrollRunItemCategories(items, activeWorkCategories);

            // Phase 5: create adjustments for each active adjustment category
            List<PayrollAdjustmentCategory> activeAdjCategories =
                    payrollAdjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();
            createPayrollAdjustments(items, activeAdjCategories, userId);

            log.info("[PayrollInit] Completed initialization for {}/{}: payrollRunId={}, items={}, monthlyReports={}",
                    year, month, payrollRun.getId(), items.size(), monthlyReports.size());

        } catch (Exception ex) {
            log.error("[PayrollInit] Failed initialization for {}/{}: {}", year, month, ex.getMessage(), ex);
        }
    }

    // ─── Phase 1: Monthly reports ────────────────────────────────────────────

    @Transactional
    public List<MonthlyReport> createMonthlyReports(List<Long> employeeRecordIds, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate startDate = ym.atDay(1);
        LocalDate endDate = ym.atEndOfMonth();

        // Load all employee records
        List<EmployeeRecord> records = employeeRecordRepository.findAllById(employeeRecordIds);

        // Find which ones already have a monthly report
        Set<Long> alreadyHaveReport = monthlyReportRepository
                .findByEmployeeRecord_IdIn(employeeRecordIds)
                .stream()
                .map(mr -> mr.getEmployeeRecord().getId())
                .collect(Collectors.toSet());

        List<MonthlyReport> toCreate = records.stream()
                .filter(er -> !alreadyHaveReport.contains(er.getId()))
                .map(er -> buildMonthlyReport(er, startDate, endDate))
                .toList();

        List<MonthlyReport> created = monthlyReportRepository.saveAll(toCreate);
        log.info("[PayrollInit] Created {} monthly reports (skipped {} existing)", created.size(), alreadyHaveReport.size());

        // Return ALL monthly reports for these employee records (including pre-existing)
        return monthlyReportRepository.findByEmployeeRecord_IdIn(employeeRecordIds);
    }

    private MonthlyReport buildMonthlyReport(EmployeeRecord er, LocalDate startDate, LocalDate endDate) {
        return MonthlyReport.builder()
                .employeeRecord(er)
                .startDate(startDate)
                .endDate(endDate)
                .totalShiftMinutes(0)
                .totalWorkMinutes(0)
                .totalAbsencePaidMinutes(0)
                .totalAbsenceUnpaidMinutes(0)
                .totalAbsenceMinutes(0)
                .totalSickLeavePaidMinutes(0)
                .totalSickLeaveUnpaidMinutes(0)
                .totalSickLeaveMinutes(0)
                .totalApprovedMinutes(0)
                .totalQuantity(0)
                .totalScrap(0)
                .totalWeightedNormMinutes(BigDecimal.ZERO)
                .mealAllowanceNum(0)
                .calcVersion(0)
                .version(0)
                .status("OPEN")
                .build();
    }

    // ─── Phase 2: Payroll run ─────────────────────────────────────────────────

    @Transactional
    public PayrollRun getOrCreatePayrollRun(int year, int month, Long userId) {
        return payrollRunRepository.findFirstByReportYearAndReportMonth(year, month)
                .orElseGet(() -> {
                    PayrollRun run = new PayrollRun();
                    run.setReportYear(year);
                    run.setReportMonth(month);
                    run.setRunCode("RUN-" + year + "-" + String.format("%02d", month));
                    run.setStatus("DRAFT");
                    run.setCreatedAt(OffsetDateTime.now());
                    if (userId != null) {
                        User userRef = new User();
                        userRef.setId(userId);
                        run.setCreatedBy(userRef);
                    }
                    PayrollRun saved = payrollRunRepository.save(run);
                    log.info("[PayrollInit] Created PayrollRun id={} for {}/{}", saved.getId(), year, month);
                    return saved;
                });
    }

    // ─── Phase 3: Payroll run items ───────────────────────────────────────────

    @Transactional
    public List<PayrollRunItem> createPayrollRunItems(PayrollRun payrollRun, List<MonthlyReport> monthlyReports) {
        // Load existing items for this run to avoid duplicates
        List<PayrollRunItem> existing = payrollRunItemRepository.findByPayrollRun_Id(payrollRun.getId());
        Set<Long> existingEmployeeIds = existing.stream()
                .map(item -> item.getEmployee().getId())
                .collect(Collectors.toSet());

        List<PayrollRunItem> toCreate = monthlyReports.stream()
                .filter(mr -> {
                    Employee emp = mr.getEmployeeRecord().getEmployee();
                    return !existingEmployeeIds.contains(emp.getId());
                })
                .map(mr -> buildPayrollRunItem(payrollRun, mr))
                .toList();

        List<PayrollRunItem> created = payrollRunItemRepository.saveAll(toCreate);
        log.info("[PayrollInit] Created {} payroll run items (skipped {} existing)", created.size(), existing.size());

        return payrollRunItemRepository.findByPayrollRun_Id(payrollRun.getId());
    }

    private PayrollRunItem buildPayrollRunItem(PayrollRun payrollRun, MonthlyReport mr) {
        PayrollRunItem item = new PayrollRunItem();
        item.setPayrollRun(payrollRun);
        item.setEmployee(mr.getEmployeeRecord().getEmployee());
        item.setMonthlyReport(mr);
        item.setTotalShiftMinutes(0);
        item.setTotalWorkMinutes(0);
        item.setTotalAbsenceMinutes(0);
        item.setTotalPaidAbsenceMinutes(0);
        item.setTotalUnpaidAbsenceMinutes(0);
        item.setTotalCompensatedMinutes(0);
        item.setTotalApprovedMinutes(0);
        item.setTotalQuantity(0);
        item.setTotalScrap(0);
        item.setTotalEffectiveMinutes(BigDecimal.ZERO);
        item.setRemoveMealAllowance(false);
        item.setRemoveTransportAllowance(false);
        item.setStatus("DRAFT");
        item.setHourlyRate(BigDecimal.ZERO);
        item.setBaseAmount(BigDecimal.ZERO);
        item.setBonusAmount(BigDecimal.ZERO);
        item.setAdjustmentAmount(BigDecimal.ZERO);
        item.setCurrencyCode("RSD");
        item.setCalcVersion(1);
        item.setTotalWorkDays(0);
        item.setTotalPaidDays(0);
        item.setTotalAbsenceDays(0);
        item.setCreatedAt(OffsetDateTime.now());
        return item;
    }

    // ─── Phase 4: Item categories ─────────────────────────────────────────────

    @Transactional
    public void createPayrollRunItemCategories(List<PayrollRunItem> items,
                                               List<WorkCodeCategory> activeWorkCategories) {
        if (activeWorkCategories.isEmpty()) return;

        // Load existing categories to skip duplicates: key = itemId + workCodeCategoryId
        List<PayrollRunItemCategory> existing = payrollRunItemCategoryRepository
                .findByPayrollRunItem_IdIn(items.stream().map(PayrollRunItem::getId).toList());

        Set<String> existingKeys = existing.stream()
                .map(c -> c.getPayrollRunItem().getId() + ":" + c.getWorkCodeCategory().getId())
                .collect(Collectors.toSet());

        List<PayrollRunItemCategory> toCreate = items.stream()
                .flatMap(item -> activeWorkCategories.stream()
                        .filter(wcc -> !existingKeys.contains(item.getId() + ":" + wcc.getId()))
                        .map(wcc -> buildItemCategory(item, wcc)))
                .toList();

        payrollRunItemCategoryRepository.saveAll(toCreate);
        log.info("[PayrollInit] Created {} payroll run item categories", toCreate.size());
    }

    private PayrollRunItemCategory buildItemCategory(PayrollRunItem item, WorkCodeCategory wcc) {
        PayrollRunItemCategory cat = new PayrollRunItemCategory();
        cat.setPayrollRunItem(item);
        cat.setWorkCodeCategory(wcc);
        // source_type comes from the work_code_category type field
        cat.setSourceType(wcc.getType());
        cat.setTotalMinutes(0);
        cat.setTotalPaidMinutes(0);
        cat.setTotalQuantity(0);
        cat.setTotalScrap(0);
        cat.setWeightedNormMinutes(BigDecimal.ZERO);
        cat.setCategoryCoefficientSnapshot(BigDecimal.ZERO);
        cat.setEffectiveMinutes(BigDecimal.ZERO);
        cat.setHourlyRate(BigDecimal.ZERO);
        cat.setAmount(BigDecimal.ZERO);
        cat.setCreatedAt(OffsetDateTime.now());
        return cat;
    }

    // ─── Phase 5: Payroll adjustments ────────────────────────────────────────

    @Transactional
    public void createPayrollAdjustments(List<PayrollRunItem> items,
                                         List<PayrollAdjustmentCategory> activeAdjCategories,
                                         Long userId) {
        if (activeAdjCategories.isEmpty()) return;

        // Load existing adjustments to skip duplicates: key = itemId + adjCategoryId
        List<PayrollAdjustment> existing = payrollAdjustmentRepository
                .findByPayrollRunItem_IdIn(items.stream().map(PayrollRunItem::getId).toList());

        Set<String> existingKeys = existing.stream()
                .map(a -> a.getPayrollRunItem().getId() + ":" + a.getPayrollAdjustmentCategory().getId())
                .collect(Collectors.toSet());

        User createdByRef = null;
        if (userId != null) {
            createdByRef = new User();
            createdByRef.setId(userId);
        }
        final User createdBy = createdByRef;

        List<PayrollAdjustment> toCreate = items.stream()
                .flatMap(item -> activeAdjCategories.stream()
                        .filter(adjCat -> !existingKeys.contains(item.getId() + ":" + adjCat.getId()))
                        .map(adjCat -> buildAdjustment(item, adjCat, createdBy)))
                .toList();

        payrollAdjustmentRepository.saveAll(toCreate);
        log.info("[PayrollInit] Created {} payroll adjustments", toCreate.size());
    }

    private PayrollAdjustment buildAdjustment(PayrollRunItem item,
                                               PayrollAdjustmentCategory adjCat,
                                               User createdBy) {
        PayrollAdjustment adj = new PayrollAdjustment();
        adj.setPayrollRunItem(item);
        adj.setPayrollAdjustmentCategory(adjCat);
        // Inherit fields from adjustment category
        adj.setAdjustmentType(adjCat.getType());
        adj.setAmount(adjCat.getDefaultValue() != null ? adjCat.getDefaultValue() : BigDecimal.ZERO);
        adj.setIsApplied(false);
        adj.setCreatedBy(createdBy);
        adj.setCreatedAt(OffsetDateTime.now());
        return adj;
    }
}

