package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScope;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollRunInitializationTxService {

    private static final String CODE_PHONE_PREVIOUS_MONTH = "PHONE_PREVIOUS_MONTH";
    private static final String CODE_PHONE_CURRENT_MONTH = "PHONE_CURRENT_MONTH";

    private final EmployeeRecordRepository employeeRecordRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;
    private final PayrollAdjustmentRepository payrollAdjustmentRepository;

    @Transactional
    public void createMonthlyReports(List<Long> employeeRecordIds, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate startDate = ym.atDay(1);
        LocalDate endDate = ym.atEndOfMonth();

        List<EmployeeRecord> records = employeeRecordRepository.findAllById(employeeRecordIds);

        Set<Long> alreadyHaveReport = monthlyReportRepository
                .findByEmployeeRecord_IdIn(employeeRecordIds)
                .stream()
                .map(mr -> mr.getEmployeeRecord().getId())
                .collect(Collectors.toSet());

        List<MonthlyReport> toCreate = records.stream()
                .filter(er -> !alreadyHaveReport.contains(er.getId()))
                .map(er -> buildMonthlyReport(er, startDate, endDate))
                .toList();

        monthlyReportRepository.saveAll(toCreate);
        log.info("[PayrollInit] Created {} monthly reports (skipped {} existing)", toCreate.size(), alreadyHaveReport.size());
    }

    @Transactional
    public List<MonthlyReport> loadMonthlyReportsWithEmployee(List<Long> employeeRecordIds) {
        return monthlyReportRepository.findByEmployeeRecord_IdInWithEmployee(employeeRecordIds);
    }

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

    @Transactional
    public List<PayrollRunItem> createPayrollRunItems(PayrollRun payrollRun, List<MonthlyReport> monthlyReports) {
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

    /**
     * @param scopes employee id -> what their compensation scheme allows across
     *               this payroll period. An employee absent from the map is
     *               unrestricted: payroll initialisation is not the place to
     *               refuse somebody, and the work-date resolver already rejected
     *               anything they should not have recorded.
     */
    @Transactional
    public void createPayrollRunItemCategories(List<PayrollRunItem> items,
                                               List<WorkCodeCategory> activeWorkCategories,
                                               Map<Long, PayrollSchemeScope> scopes) {
        if (activeWorkCategories.isEmpty()) {
            return;
        }

        List<PayrollRunItemCategory> existing = payrollRunItemCategoryRepository
                .findByPayrollRunItem_IdIn(items.stream().map(PayrollRunItem::getId).toList());

        Set<String> existingKeys = existing.stream()
                .map(c -> c.getPayrollRunItem().getId() + ":" + c.getWorkCodeCategory().getId())
                .collect(Collectors.toSet());

        // A restricted employee gets rows only for the categories their scheme
        // can actually produce, so their payslip is not padded with a dozen zero
        // lines for work they are not allowed to do.
        List<PayrollRunItemCategory> toCreate = items.stream()
                .flatMap(item -> {
                    PayrollSchemeScope scope = scopeOf(scopes, item);
                    return activeWorkCategories.stream()
                            .filter(wcc -> scope == null || scope.allowsWorkCategory(wcc.getId()))
                            .filter(wcc -> !existingKeys.contains(item.getId() + ":" + wcc.getId()))
                            .map(wcc -> buildItemCategory(item, wcc));
                })
                .toList();

        payrollRunItemCategoryRepository.saveAll(toCreate);
        log.info("[PayrollInit] Created {} payroll run item categories", toCreate.size());
    }

    @Transactional
    public void createPayrollAdjustments(List<PayrollRunItem> items,
                                         List<PayrollAdjustmentCategory> activeAdjCategories,
                                         Map<Long, PayrollSchemeScope> scopes,
                                         Long userId) {
        if (activeAdjCategories.isEmpty()) {
            return;
        }

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

        // Excluding the row is what actually suppresses meal allowance, transport
        // and the monthly bonus: PayrollRunItemService updates those amounts
        // through findByItemIdAndCategoryCode(...).ifPresent(...), so with no row
        // there is nothing to update. The item-level meal and transport columns
        // are zeroed separately — they are added to the total directly, not
        // through the adjustment.
        List<PayrollAdjustment> toCreate = items.stream()
                .flatMap(item -> {
                    PayrollSchemeScope scope = scopeOf(scopes, item);
                    return activeAdjCategories.stream()
                            .filter(adjCat -> scope == null || scope.allowsAdjustmentCategory(adjCat.getId()))
                            .filter(adjCat -> !existingKeys.contains(item.getId() + ":" + adjCat.getId()))
                            .map(adjCat -> buildAdjustment(item, adjCat, createdBy));
                })
                .toList();

        payrollAdjustmentRepository.saveAll(toCreate);
        log.info("[PayrollInit] Created {} payroll adjustments", toCreate.size());
    }

    @Transactional
    public void populatePreviousMonthData(List<PayrollRunItem> items) {
        for (PayrollRunItem item : items) {
            if (item.getPeriod() == null || item.getEmployee() == null) {
                continue;
            }

            LocalDate prevPeriod = item.getPeriod().minusMonths(1).withDayOfMonth(1);
            List<PayrollRunItem> prevItems = payrollRunItemRepository
                    .findByEmployee_IdAndPeriod(item.getEmployee().getId(), prevPeriod);

            if (prevItems.isEmpty()) {
                continue;
            }
            PayrollRunItem prev = prevItems.getFirst();

            if (prev.getNetPayableAmount() != null) {
                item.setPreviousNetPayableAmount(prev.getNetPayableAmount());
                payrollRunItemRepository.save(item);
            }

            // FROM THE PREVIOUS MONTH'S LINE, not a column beside it. The phone
            // was written to both and only the column was read here, so a figure
            // entered on the line alone would never have been charged.
            BigDecimal prevPhone = payrollAdjustmentRepository
                    .findByItemIdAndCategoryCode(prev.getId(), CODE_PHONE_CURRENT_MONTH)
                    .map(PayrollAdjustment::getAmount)
                    .orElse(null);

            if (prevPhone != null && prevPhone.compareTo(BigDecimal.ZERO) != 0) {
                final BigDecimal phoneAmt = prevPhone;
                payrollAdjustmentRepository
                        .findByItemIdAndCategoryCode(item.getId(), CODE_PHONE_PREVIOUS_MONTH)
                        .ifPresent(adj -> {
                            adj.setAmount(phoneAmt);
                            adj.setSystemAmount(phoneAmt);
                            adj.setUpdatedAt(OffsetDateTime.now());
                            payrollAdjustmentRepository.save(adj);
                        });
            }
        }
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

    private PayrollRunItem buildPayrollRunItem(PayrollRun payrollRun, MonthlyReport mr) {
        // No price is read here any more. The meal and transport rates were
        // stamped onto the item's mirror columns at initialisation; the columns are
        // gone and the calculation reads both prices for itself, at the month's
        // last day. These two lookups outlived the columns they fed.

        PayrollRunItem item = new PayrollRunItem();
        item.setPayrollRun(payrollRun);
        item.setEmployee(mr.getEmployeeRecord().getEmployee());
        item.setMonthlyReport(mr);
        item.setPeriod(mr.getStartDate());

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
        item.setPerformanceRate(BigDecimal.ZERO);
        item.setApprovedPerformanceRate(BigDecimal.ZERO);
        item.setPerformanceCoefficient(BigDecimal.ZERO);
        item.setTotalWorkDays(0);
        item.setTotalPaidDays(0);
        item.setTotalAbsenceDays(0);

        item.setTotalPayrollMinutes(0);

        item.setTotalNetEarnings(BigDecimal.ZERO);
        BigDecimal empRate = mr.getEmployeeRecord().getEmployee().getHourlyRate() != null
                ? mr.getEmployeeRecord().getEmployee().getHourlyRate() : BigDecimal.ZERO;
        item.setHourlyRate(empRate);
        item.setHourlyRateSystem(empRate);
        item.setHourlyRateOverridden(false);



        item.setPreviouslyPaidAmount(BigDecimal.ZERO);
        item.setPreviousNetPayableAmount(BigDecimal.ZERO);
        item.setCurrentBalanceAmount(BigDecimal.ZERO);
        item.setNetPayableAmount(BigDecimal.ZERO);

        item.setStatus("DRAFT");
        item.setCurrencyCode("RSD");
        item.setCalcVersion(1);
        item.setCreatedAt(OffsetDateTime.now());
        return item;
    }

    private PayrollSchemeScope scopeOf(Map<Long, PayrollSchemeScope> scopes, PayrollRunItem item) {
        if (scopes == null || item.getEmployee() == null) {
            return null;
        }
        return scopes.get(item.getEmployee().getId());
    }

    private PayrollRunItemCategory buildItemCategory(PayrollRunItem item, WorkCodeCategory wcc) {
        PayrollRunItemCategory cat = new PayrollRunItemCategory();
        cat.setPayrollRunItem(item);
        cat.setWorkCodeCategory(wcc);
        cat.setSourceType(wcc.getType());
        cat.setTotalMinutes(0);
        cat.setTotalPaidMinutes(0);
        cat.setTotalQuantity(0);
        cat.setTotalScrap(0);
        cat.setWeightedNormMinutes(BigDecimal.ZERO);
        cat.setCategoryCoefficientSnapshot(wcc.getNormMultiplier() != null
                ? BigDecimal.valueOf(wcc.getNormMultiplier()) : BigDecimal.ONE);
        cat.setEffectiveMinutes(BigDecimal.ZERO);
        BigDecimal categoryHourlyRate = Boolean.TRUE.equals(wcc.getFixedHourlyRate()) && wcc.getHourlyRate() != null
                ? wcc.getHourlyRate()
                : (item.getHourlyRate() != null ? item.getHourlyRate() : BigDecimal.ZERO);
        cat.setHourlyRate(categoryHourlyRate);
        cat.setAmount(BigDecimal.ZERO);
        cat.setCategoryAffectsNormSnapshot("WORK".equals(wcc.getType()));
        cat.setCategoryAffectsBonusSnapshot("WORK".equals(wcc.getType()));
        cat.setCreatedAt(OffsetDateTime.now());
        return cat;
    }

    private PayrollAdjustment buildAdjustment(PayrollRunItem item,
                                              PayrollAdjustmentCategory adjCat,
                                              User createdBy) {
        PayrollAdjustment adj = new PayrollAdjustment();
        adj.setPayrollRunItem(item);
        adj.setPayrollAdjustmentCategory(adjCat);
        adj.setSystemAmount(BigDecimal.ZERO);
        adj.setAmount(BigDecimal.ZERO);
        adj.setIsOverridden(false);
        adj.setIsApplied(true);
        adj.setCreatedBy(createdBy);
        adj.setCreatedAt(OffsetDateTime.now());
        return adj;
    }
}

