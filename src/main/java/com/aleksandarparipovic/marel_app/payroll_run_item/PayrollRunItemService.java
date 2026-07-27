package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryNameResolver;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScope;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScopeService;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryNameResolver;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategory;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRun;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.AdjustmentPatchDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollAdjustmentDetailDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollAdjustmentSectionDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemCategoryDetailDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemDetailResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPermissionsDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.RecentPayrollSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemActivityDto;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollRunItemService {

    private static final String STATUS_LOCKED = "LOCKED";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String DEFAULT_CURRENCY = "RSD";
    private static final int DEFAULT_CALC_VERSION = 1;

    // impact codes
    private static final String DEDUCTION_MINUS = "DEDUCTION_MINUS";

    private final PayrollRunItemRepository payrollRunItemRepository;
    private final PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;
    private final PayrollAdjustmentRepository payrollAdjustmentRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final MonthlyReportCategoryRepository monthlyReportCategoryRepository;
    private final AppSettingService appSettingService;
    private final EntityReferenceProvider referenceProvider;
    private final CurrentUserService currentUserService;
    private final WorkCodeCategoryNameResolver workCodeCategoryNameResolver;
    private final PayrollAdjustmentCategoryNameResolver payrollAdjustmentCategoryNameResolver;
    private final PayrollAdjustmentCategoryRepository payrollAdjustmentCategoryRepository;
    private final PayrollSchemeScopeService payrollSchemeScopeService;

    // ─── Standard CRUD ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PayrollRunItem> findAll() {
        return payrollRunItemRepository.findAll();
    }

    @Transactional(readOnly = true)
    public PayrollRunItem findById(Long id) {
        return payrollRunItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found"));
    }

    @Transactional(readOnly = true)
    public List<RecentPayrollSummaryDto> getRecentByEmployee(Long employeeId, int size) {
        return payrollRunItemRepository.findRecentByEmployeeId(employeeId, PageRequest.of(0, size))
                .stream()
                .map(RecentPayrollSummaryDto::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PayrollRunItemActivityDto> getLastActivityByMonth(int year, int month) {
        Long userId = currentUserService.getCurrentUserId();
        return payrollRunItemRepository.findItemLastActivityByUserAndMonth(userId, year, month);
    }

    @Transactional
    public PayrollRunItem create(PayrollRunItemCreateRequest request) {
        PayrollRunItem entity = new PayrollRunItem();
        entity.setId(null);
        entity.setPayrollRun(referenceProvider.getRequiredReference(PayrollRun.class, request.getPayrollRunId(), "payrollRunId"));
        entity.setEmployee(referenceProvider.getRequiredReference(Employee.class, request.getEmployeeId(), "employeeId"));
        if (request.getMonthlyReportId() != null) {
            MonthlyReport mr = monthlyReportRepository.findById(request.getMonthlyReportId())
                    .orElseThrow(() -> new IllegalArgumentException("MonthlyReport not found: " + request.getMonthlyReportId()));
            entity.setMonthlyReport(mr);
            entity.setPeriod(mr.getStartDate());
        }
        initializeCreateDefaults(entity);
        return payrollRunItemRepository.save(entity);
    }

    @Transactional
    public PayrollRunItem update(Long id, PayrollRunItem entity) {
        if (!payrollRunItemRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRunItem not found");
        }
        entity.setId(id);
        return payrollRunItemRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!payrollRunItemRepository.existsById(id)) {
            throw new IllegalArgumentException("PayrollRunItem not found");
        }
        payrollRunItemRepository.deleteById(id);
    }

    // ─── Version-based access (lazy recalculation on demand) ────────────────

    /**
     * Returns the payroll run item, automatically recalculating it if the linked
     * {@code monthly_reports.version} has advanced past {@code based_on_version}.
     *
     * <p>Rules:
     * <ul>
     *   <li>Status {@code LOCKED} → always return as-is, never recalculate.</li>
     *   <li>No linked monthly report → return as-is.</li>
     *   <li>{@code monthly_reports.version == based_on_version} → up to date, return as-is.</li>
     *   <li>Version mismatch → recalculate, persist, return fresh item.</li>
     * </ul>
     */
    @Transactional
    public PayrollRunItem getForPayrollAccess(Long id) {
        PayrollRunItem item = payrollRunItemRepository.findByIdWithMonthlyReport(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found: " + id));

        // LOCKED items are immutable snapshots — ignore any version mismatch
        if (STATUS_LOCKED.equals(item.getStatus())) {
            log.debug("PayrollRunItem {} is LOCKED – skipping version check", id);
            return item;
        }

        MonthlyReport mr = item.getMonthlyReport();
        if (mr == null) {
            log.debug("PayrollRunItem {} has no linked MonthlyReport – skipping version check", id);
            return item;
        }

        Integer latestVersion = mr.getVersion();
        Integer usedVersion   = item.getBasedOnVersion();
        boolean versionStale  = latestVersion != null && !latestVersion.equals(usedVersion);
        boolean flaggedForRecalc = Boolean.TRUE.equals(item.getNeedsRecalculation());

        if (!versionStale && !flaggedForRecalc) {
            log.debug("PayrollRunItem {} is up-to-date at version {}", id, latestVersion);
            return item;
        }

        if (flaggedForRecalc) {
            log.info("PayrollRunItem {} is flagged for recalculation (needs_recalculation=true) – recalculating", id);
        } else {
            log.info("PayrollRunItem {} is stale (based_on_version={}, monthly_report.version={}) – recalculating",
                    id, usedVersion, latestVersion);
        }
        return recalculateFromMonthlyReport(item, mr);
    }

    /**
     * Convenience lookup by payroll run + employee with the same version-check semantics.
     * Creates a new item skeleton if none exists yet for this run/employee combination.
     */
    @Transactional
    public List<PayrollRunItem> getForPayrollRun(Long payrollRunId) {
        return payrollRunItemRepository.findByPayrollRun_Id(payrollRunId).stream()
                .map(item -> STATUS_LOCKED.equals(item.getStatus()) ? item : refreshIfStale(item))
                .toList();
    }

    // ─── Details view ───────────────────────────────────────────────────────

    @Transactional
    public PayrollRunItemDetailResponse getDetails(Long monthlyReportId) {
        return getDetails(monthlyReportId, null);
    }

    /**
     * @param requestedLocale the language for category and adjustment display
     *                        names. {@code null} falls back to the employee's
     *                        own {@code preferred_locale}, which is what payroll
     *                        documents are produced in.
     *
     * <p>The locale selects display names and nothing else. Every amount on this
     * response is identical in every locale — the translation maps are read
     * strictly after the calculation.
     */
    @Transactional
    public PayrollRunItemDetailResponse getDetails(Long monthlyReportId, String requestedLocale) {
        PayrollRunItem item = payrollRunItemRepository.findByMonthlyReport_Id(monthlyReportId)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found for monthlyReportId: " + monthlyReportId));

        // version check — recalculate if monthly report has advanced
        item = getForPayrollAccess(item.getId());

        String locale = AppLocales.normalize(
                requestedLocale != null ? requestedLocale : employeeLocaleFor(item));

        // Two queries, once per response — never one per category or per
        // adjustment inside the loops below.
        Map<Long, String> workCodeNames = workCodeCategoryNameResolver.translationsFor(locale);
        Map<Long, String> adjustmentNames = payrollAdjustmentCategoryNameResolver.translationsFor(locale);

        List<PayrollRunItemCategoryDetailDto> categories =
                payrollRunItemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(item.getId())
                        .stream()
                        .map(c -> new PayrollRunItemCategoryDetailDto(c, workCodeNames))
                        .toList();

        List<PayrollAdjustmentSectionDto> adjustments =
                payrollAdjustmentRepository.findByPayrollRunItemIdWithCategory(item.getId())
                        .stream()
                        .map(a -> new PayrollAdjustmentDetailDto(a, adjustmentNames))
                        .collect(java.util.stream.Collectors.groupingBy(
                                dto -> dto.getSectionCode() != null ? dto.getSectionCode() : ""
                        ))
                        .entrySet().stream()
                        .map(entry -> {
                            List<PayrollAdjustmentDetailDto> sorted = entry.getValue().stream()
                                    .sorted(java.util.Comparator.comparingInt(
                                            d -> d.getSortOrder() != null ? d.getSortOrder() : Integer.MAX_VALUE))
                                    .toList();
                            Integer sectionOrder = sorted.stream()
                                    .map(PayrollAdjustmentDetailDto::getSectionOrder)
                                    .filter(java.util.Objects::nonNull)
                                    .findFirst().orElse(Integer.MAX_VALUE);
                            return new PayrollAdjustmentSectionDto(entry.getKey(), sectionOrder, sorted);
                        })
                        .sorted(java.util.Comparator.comparingInt(PayrollAdjustmentSectionDto::getSectionOrder))
                        .toList();

        PayrollRunItemPermissionsDto permissions = resolvePermissions();

        return new PayrollRunItemDetailResponse(
                new PayrollRunItemResponse(item),
                categories,
                adjustments,
                permissions
        );
    }

    /**
     * The locale a payroll document for this item should be produced in.
     *
     * <p>The EMPLOYEE's preference, not the clerk's: a payslip is a document
     * about the employee. Never derived from is_foreigner or the compensation
     * scheme.
     */
    private String employeeLocaleFor(PayrollRunItem item) {
        MonthlyReport mr = item.getMonthlyReport();
        if (mr == null || mr.getEmployeeRecord() == null || mr.getEmployeeRecord().getEmployee() == null) {
            return AppLocales.DEFAULT;
        }
        return mr.getEmployeeRecord().getEmployee().getPreferredLocale();
    }

    // ─── Patch ──────────────────────────────────────────────────────────────

    // Stable calculation_key values - must match payroll_adjustment_categories.calculation_key in DB
    // category code values — must match payroll_adjustment_categories.code in DB
    private static final String CAT_CODE_TRANSPORT     = "TRANSPORT_ALLOWANCE";
    private static final String CAT_CODE_BONUS         = "MONTHLY_BONUS";
    private static final String CAT_CODE_FIXED_SALARY  = "FIXED_SALARY";
    private static final String SECTION_SETTLEMENTS    = "SETTLEMENTS";
    private static final String SECTION_ADDITIONS      = "ADDITIONS";

    @Transactional
    public PayrollRunItemDetailResponse patch(Long id, PayrollRunItemPatchRequest req) {
        PayrollRunItem item = payrollRunItemRepository.findByIdWithMonthlyReport(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found: " + id));

        if (STATUS_LOCKED.equals(item.getStatus())) {
            throw new IllegalStateException("PayrollRunItem " + id + " is LOCKED and cannot be edited");
        }

        // ── 1. Simple fields (no cascade) ────────────────────────────────────
        if (req.getNote() != null) {
            item.setNote(req.getNote());
        }
        if (req.getCurrentMonthTelephone() != null) {
            item.setCurrentMonthTelephone(req.getCurrentMonthTelephone().setScale(2, RoundingMode.HALF_UP));
        }
        if (req.getManualAdjustedMinutes() != null) {
            item.setManualAdjustedMinutes(req.getManualAdjustedMinutes());
            int base = item.getTotalWorkMinutes() != null ? item.getTotalWorkMinutes() : 0;
            item.setTotalPayrollMinutes(base + req.getManualAdjustedMinutes());
        }

        // ── 2. mealAllowanceUnitAmount → recalc totalMealAllowanceAmount ─────
        // null = reset to system value; value == system = overridden false; value != system = overridden true
        if (req.isMealAllowanceUnitAmountPresent()) {
            BigDecimal unitAmt = req.getMealAllowanceUnitAmount() != null
                    ? req.getMealAllowanceUnitAmount().setScale(2, RoundingMode.HALF_UP)
                    : item.getMealAllowanceUnitAmountSystem();
            if (unitAmt == null) unitAmt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            item.setMealAllowanceUnitAmount(unitAmt);
            item.setMealAllowanceUnitAmountOverridden(
                    item.getMealAllowanceUnitAmountSystem() != null &&
                    unitAmt.compareTo(item.getMealAllowanceUnitAmountSystem()) != 0);
            item.setTotalMealAllowanceAmount(
                    unitAmt.multiply(BigDecimal.valueOf(
                            item.getMealAllowanceCount() != null ? item.getMealAllowanceCount() : 0))
                           .setScale(2, RoundingMode.HALF_UP));
        }

        // ── 3. totalTransportAllowanceAmount → sync TRANSPORT adj ─────────────
        if (req.isTotalTransportAllowanceAmountPresent()) {
            BigDecimal transport = req.getTotalTransportAllowanceAmount() != null
                    ? req.getTotalTransportAllowanceAmount().setScale(2, RoundingMode.HALF_UP)
                    : item.getTotalTransportAllowanceAmountSystem();
            if (transport == null) transport = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            item.setTotalTransportAllowanceAmount(transport);
            item.setTotalTransportAllowanceAmountOverridden(
                    item.getTotalTransportAllowanceAmountSystem() != null &&
                    transport.compareTo(item.getTotalTransportAllowanceAmountSystem()) != 0);
            updateAdjustmentByCategoryCode(id, CAT_CODE_TRANSPORT, transport, true);
        }

        // ── 4. Bonus fields → sync BONUS adj ─────────────────────────────────
        if (req.isBaseBonusAmountPresent()) {
            BigDecimal amt = req.getBaseBonusAmount() != null
                    ? req.getBaseBonusAmount().setScale(2, RoundingMode.HALF_UP)
                    : item.getBaseBonusAmountSystem();
            if (amt == null) amt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            item.setBaseBonusAmount(amt);
            item.setBaseBonusAmountOverridden(
                    item.getBaseBonusAmountSystem() != null &&
                    amt.compareTo(item.getBaseBonusAmountSystem()) != 0);
        }
        if (req.isBonusCorrectionAmountPresent()) {
            BigDecimal amt = req.getBonusCorrectionAmount() != null
                    ? req.getBonusCorrectionAmount().setScale(2, RoundingMode.HALF_UP)
                    : item.getBonusCorrectionAmountSystem();
            if (amt == null) amt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            item.setBonusCorrectionAmount(amt);
            item.setBonusCorrectionAmountOverridden(
                    item.getBonusCorrectionAmountSystem() != null &&
                    amt.compareTo(item.getBonusCorrectionAmountSystem()) != 0);
        }
        if (req.isTotalBonusAmountPresent()) {
            if (req.getTotalBonusAmount() != null) {
                BigDecimal bonus = req.getTotalBonusAmount().setScale(2, RoundingMode.HALF_UP);
                item.setTotalBonusAmount(bonus);
                item.setTotalBonusAmountOverridden(
                        item.getTotalBonusAmountSystem() != null &&
                        bonus.compareTo(item.getTotalBonusAmountSystem()) != 0);
                updateAdjustmentByCategoryCode(id, CAT_CODE_BONUS, bonus, true);
            } else {
                // null sent → reset override, auto-calculate from components
                item.setTotalBonusAmountOverridden(false);
                BigDecimal base       = item.getBaseBonusAmount()       != null ? item.getBaseBonusAmount()       : BigDecimal.ZERO;
                BigDecimal correction = item.getBonusCorrectionAmount() != null ? item.getBonusCorrectionAmount() : BigDecimal.ZERO;
                BigDecimal total      = base.add(correction).setScale(2, RoundingMode.HALF_UP);
                item.setTotalBonusAmount(total);
                updateAdjustmentByCategoryCode(id, CAT_CODE_BONUS, total, true);
            }
        } else if (req.isBaseBonusAmountPresent() || req.isBonusCorrectionAmountPresent()) {
            if (!Boolean.TRUE.equals(item.getTotalBonusAmountOverridden())) {
                BigDecimal base       = item.getBaseBonusAmount()       != null ? item.getBaseBonusAmount()       : BigDecimal.ZERO;
                BigDecimal correction = item.getBonusCorrectionAmount() != null ? item.getBonusCorrectionAmount() : BigDecimal.ZERO;
                BigDecimal total      = base.add(correction).setScale(2, RoundingMode.HALF_UP);
                item.setTotalBonusAmount(total);
                updateAdjustmentByCategoryCode(id, CAT_CODE_BONUS, total, true);
            }
        }

        // ── 5. Individual adjustment patches ─────────────────────────────────
        if (req.getAdjustments() != null && !req.getAdjustments().isEmpty()) {
            for (AdjustmentPatchDto adjPatch : req.getAdjustments()) {
                PayrollAdjustment adj = payrollAdjustmentRepository.findByIdWithCategory(adjPatch.getId())
                        .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustment not found: " + adjPatch.getId()));

                if (!adj.getPayrollRunItem().getId().equals(id)) {
                    throw new IllegalArgumentException(
                            "PayrollAdjustment " + adjPatch.getId() + " does not belong to PayrollRunItem " + id);
                }

                boolean overridden = Boolean.TRUE.equals(adj.getIsOverridden());
                if (adjPatch.getQuantity() != null) {
                    adj.setQuantity(adjPatch.getQuantity().setScale(4, RoundingMode.HALF_UP));
                    if (adj.getSystemQuantity() != null && adjPatch.getQuantity().compareTo(adj.getSystemQuantity()) != 0)
                        overridden = true;
                }
                if (adjPatch.getUnitAmount() != null) {
                    adj.setUnitAmount(adjPatch.getUnitAmount().setScale(4, RoundingMode.HALF_UP));
                    if (adj.getSystemUnitAmount() != null && adjPatch.getUnitAmount().compareTo(adj.getSystemUnitAmount()) != 0)
                        overridden = true;
                }
                if (adjPatch.getAmount() != null) {
                    adj.setAmount(adjPatch.getAmount().setScale(2, RoundingMode.HALF_UP));
                    if (adj.getSystemAmount() != null && adjPatch.getAmount().compareTo(adj.getSystemAmount()) != 0)
                        overridden = true;
                }
                if (adjPatch.getIsApplied() != null) {
                    adj.setIsApplied(adjPatch.getIsApplied());
                }
                if (adjPatch.getNote() != null) {
                    adj.setNote(adjPatch.getNote());
                }
                adj.setIsOverridden(overridden);
                adj.setUpdatedAt(OffsetDateTime.now());
                payrollAdjustmentRepository.save(adj);
            }
        }

        // ── 6. SETTLEMENTS recalculation (now handled inside recalculateSummaryTotals) ─
        // No explicit call needed — recalculateSummaryTotals() at step 8 covers everything.

        // ── 7. totalNetEarnings OR hourlyRate branch ──────────────────────────
        if (req.getTotalNetEarnings() != null) {
            // totalNetEarnings mode: force hourlyRate=0, zero all categories,
            // then set FIXED_SALARY = netEarnings - meal - SUM(applied ADDITIONS excl. FIXED_SALARY)
            BigDecimal netEarnings = req.getTotalNetEarnings().setScale(2, RoundingMode.HALF_UP);
            item.setTotalNetEarnings(netEarnings);
            item.setHourlyRate(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setHourlyRateOverridden(
                    item.getHourlyRateSystem() != null &&
                    BigDecimal.ZERO.compareTo(item.getHourlyRateSystem()) != 0);
            recalculateCategoriesForHourlyRate(id, BigDecimal.ZERO);

            BigDecimal meal = item.getTotalMealAllowanceAmount() != null ? item.getTotalMealAllowanceAmount() : BigDecimal.ZERO;

            // SUM of all applied ADDITIONS adjustments except FIXED_SALARY itself
            BigDecimal additionsSum = payrollAdjustmentRepository
                    .findByItemIdAndSectionCode(id, SECTION_ADDITIONS)
                    .stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                            && !CAT_CODE_FIXED_SALARY.equals(a.getPayrollAdjustmentCategory().getCode()))
                    .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal fixedValue = netEarnings.subtract(meal).subtract(additionsSum)
                    .setScale(2, RoundingMode.HALF_UP);
            updateAdjustmentByCategoryCode(id, CAT_CODE_FIXED_SALARY, fixedValue, true);

        } else if (req.isHourlyRatePresent()) {
            // null = reset to system value; value == system = overridden false
            BigDecimal newRate = req.getHourlyRate() != null
                    ? req.getHourlyRate().setScale(2, RoundingMode.HALF_UP)
                    : item.getHourlyRateSystem();
            if (newRate == null) newRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            item.setHourlyRate(newRate);
            item.setHourlyRateOverridden(
                    item.getHourlyRateSystem() != null &&
                    newRate.compareTo(item.getHourlyRateSystem()) != 0);
            recalculateCategoriesForHourlyRate(id, newRate);
        }

        // ── 8. Final summary totals & persist ────────────────────────────────
        recalculateSummaryTotals(item);
        item.setUpdatedAt(OffsetDateTime.now());
        payrollRunItemRepository.save(item);

        // ── 9. Propagate phone/netPayable changes to next month's item ────────
        propagateToNextMonthItem(item);

        return getDetails(item.getMonthlyReport() != null ? item.getMonthlyReport().getId() : null);
    }

    /**
     * Populates {@link PayrollRunItemCategory} rows from the corresponding
     * {@link MonthlyReportCategory} rows for the same monthly report.
     * Matches by {@code workCodeCategory.id}.
     *
     * <p>For each matched category:
     * <ul>
     *   <li>Copies time/quantity totals from the monthly report category.</li>
     *   <li>Snapshots {@code isPaid}, {@code normMultiplier} from {@link com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory}.</li>
     *   <li>Sets {@code effectiveMinutes = totalWeightedNormMinutes} (pre-weighted by the daily recalc).</li>
     *   <li>Recalculates {@code amount = (effectiveMinutes / 60) * hourlyRate}.</li>
     *   <li>Recalculates {@code bonusAmount} using the item's {@code performanceCoefficient}
     *       if the category is paid.</li>
     * </ul>
     */
    private void populateItemCategoriesFromMonthlyReport(PayrollRunItem item, MonthlyReport mr,
                                                        PayrollSchemeScope scope) {
        List<MonthlyReportCategory> monthlyCategories =
                monthlyReportCategoryRepository.findByMonthlyReportIdWithCategory(mr.getId());

        if (monthlyCategories.isEmpty()) return;

        // Build lookup: workCodeCategoryId → MonthlyReportCategory
        java.util.Map<Long, MonthlyReportCategory> byWcc = monthlyCategories.stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> c.getWorkCodeCategory().getId(),
                        c -> c,
                        (a, b) -> a));

        List<PayrollRunItemCategory> itemCategories =
                payrollRunItemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(item.getId());

        BigDecimal hourlyRate = item.getHourlyRate() != null ? item.getHourlyRate() : BigDecimal.ZERO;
        BigDecimal performanceCoeff = item.getPerformanceCoefficient() != null
                ? item.getPerformanceCoefficient() : BigDecimal.ZERO;
        OffsetDateTime now = OffsetDateTime.now();

        for (PayrollRunItemCategory cat : itemCategories) {
            Long wccId = cat.getWorkCodeCategory().getId();
            MonthlyReportCategory mrc = byWcc.get(wccId);

            if (mrc == null) {
                // No activity for this category this month — zero it out
                cat.setTotalMinutes(0);
                cat.setTotalPaidMinutes(0);
                cat.setTotalQuantity(0);
                cat.setTotalScrap(0);
                cat.setWeightedNormMinutes(BigDecimal.ZERO);
                cat.setEffectiveMinutes(BigDecimal.ZERO);
                cat.setAmount(BigDecimal.ZERO);
                cat.setBonusAmount(BigDecimal.ZERO);
                cat.setUpdatedAt(now);
                continue;
            }

            // ── Copy totals ───────────────────────────────────────────────────
            cat.setTotalMinutes(mrc.getTotalMinutes());
            cat.setTotalPaidMinutes(mrc.getTotalPaidMinutes());
            cat.setTotalQuantity(mrc.getTotalQuantity());
            cat.setTotalScrap(mrc.getTotalScrap());
            cat.setWeightedNormMinutes(mrc.getTotalWeightedNormMinutes() != null
                    ? mrc.getTotalWeightedNormMinutes() : BigDecimal.ZERO);
            cat.setSourceType(mrc.getSourceType());

            // ── Snapshots from WorkCodeCategory ───────────────────────────────
            var wcc = cat.getWorkCodeCategory();
            cat.setCategoryIsPaidSnapshot(wcc.getIsPaid());
            cat.setCategoryAffectsNormSnapshot(wcc.getNormMultiplier() != null && wcc.getNormMultiplier() > 0);
            cat.setCategoryCoefficientSnapshot(wcc.getNormMultiplier() != null
                    ? BigDecimal.valueOf(wcc.getNormMultiplier()) : BigDecimal.ONE);

            // ── effectiveMinutes = weighted norm minutes (already weighted by daily recalc) ──
            BigDecimal effectiveMinutes = cat.getWeightedNormMinutes().multiply(cat.getCategoryCoefficientSnapshot());
            cat.setEffectiveMinutes(effectiveMinutes);

            // ── Effective hourly rate for this category ───────────────────────
            BigDecimal categoryHourlyRate = Boolean.TRUE.equals(wcc.getFixedHourlyRate()) && wcc.getHourlyRate() != null
                    ? wcc.getHourlyRate()
                    : hourlyRate;
            cat.setHourlyRate(categoryHourlyRate);

            // ── amount = (effectiveMinutes / 60) * categoryHourlyRate ─────────
            BigDecimal amount = effectiveMinutes
                    .divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP)
                    .multiply(categoryHourlyRate)
                    .setScale(2, RoundingMode.HALF_UP);
            cat.setAmount(amount);

            // ── bonusAmount = amount * performanceCoefficient (if paid) ───────
            //
            // A scheme with allows_performance_bonus = false pays no bonus at
            // all. Efficiency is NOT switched off by this: approved performance
            // already weighted the minutes that became weightedNormMinutes and
            // therefore the amount above. Only the bonus on top is removed.
            boolean bonusAllowed = scope == null || scope.allowsPerformanceBonus();
            if (bonusAllowed && Boolean.TRUE.equals(wcc.getIsPaid())
                    && performanceCoeff.compareTo(BigDecimal.ZERO) > 0) {
                cat.setCategoryAffectsBonusSnapshot(true);
                cat.setBonusAmount(amount.multiply(performanceCoeff).setScale(2, RoundingMode.HALF_UP));
            } else {
                cat.setCategoryAffectsBonusSnapshot(false);
                cat.setBonusAmount(BigDecimal.ZERO);
            }

            cat.setPerformanceCoefficient(performanceCoeff);
            cat.setUpdatedAt(now);
        }

        payrollRunItemCategoryRepository.saveAll(itemCategories);
    }

    /**
     * Recalculates hourlyRate, amount, and bonusAmount for all categories of the given item.
     * Formula: amount = (effectiveMinutes / 60) * hourlyRate
     *          bonusAmount = amount * performanceCoefficient  (only if categoryAffectsBonusSnapshot)
     */
    private void recalculateCategoriesForHourlyRate(Long itemId, BigDecimal newHourlyRate) {
        List<PayrollRunItemCategory> categories =
                payrollRunItemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(itemId);
        OffsetDateTime now = OffsetDateTime.now();
        for (PayrollRunItemCategory cat : categories) {
            var wcc = cat.getWorkCodeCategory();
            if (Boolean.TRUE.equals(wcc.getFixedHourlyRate())) {
                continue;
            }
            cat.setHourlyRate(newHourlyRate);
            BigDecimal effectiveMinutes = cat.getEffectiveMinutes() != null ? cat.getEffectiveMinutes() : BigDecimal.ZERO;
            BigDecimal amount = effectiveMinutes
                    .divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP)
                    .multiply(newHourlyRate)
                    .setScale(2, RoundingMode.HALF_UP);
            cat.setAmount(amount);

            if (Boolean.TRUE.equals(cat.getCategoryAffectsBonusSnapshot()) && cat.getPerformanceCoefficient() != null) {
                BigDecimal bonusAmount = amount.multiply(cat.getPerformanceCoefficient())
                        .setScale(2, RoundingMode.HALF_UP);
                cat.setBonusAmount(bonusAmount);
            } else {
                cat.setBonusAmount(BigDecimal.ZERO);
            }
            cat.setUpdatedAt(now);
        }
        payrollRunItemCategoryRepository.saveAll(categories);
    }

    /**
     * Finds an adjustment by category code and updates its amount and isApplied flag.
     * Used for categories where calculation_key is NULL (e.g. FIXED_SALARY).
     */
    private void updateAdjustmentByCategoryCode(Long itemId, String categoryCode, BigDecimal amount, boolean isApplied) {
        payrollAdjustmentRepository.findByItemIdAndCategoryCode(itemId, categoryCode)
                .ifPresent(adj -> {
                    adj.setAmount(amount);
                    adj.setIsApplied(isApplied);
                    adj.setIsOverridden(
                            adj.getSystemAmount() != null && amount.compareTo(adj.getSystemAmount()) != 0);
                    adj.setUpdatedAt(OffsetDateTime.now());
                    payrollAdjustmentRepository.save(adj);
                });
    }

    /**
     * Whether an adjustment category is available under this scope.
     *
     * <p>By code, because these three amounts are computed by name in this class
     * and there is no entity to hand at the point of the check. A null scope is
     * unrestricted.
     */
    private boolean allowsAdjustmentCode(PayrollSchemeScope scope, String code) {
        if (scope == null) {
            return true;
        }
        return payrollAdjustmentCategoryRepository.findByCode(code)
                .map(category -> scope.allowsAdjustmentCategory(category.getId()))
                .orElse(true);
    }

    private PayrollRunItemPermissionsDto resolvePermissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean isSupervisor = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPERVISOR"));

        return new PayrollRunItemPermissionsDto(
                isAdmin,           // canEditAdjustments
                isAdmin,           // canLock
                isAdmin || isSupervisor  // canApprove
        );
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    /**
     * Copies aggregated fields from the monthly report into the payroll item,
     * recalculates payroll hours and base pay, then recalculates all summary
     * totals from adjustment lines.
     */
    private PayrollRunItem recalculateFromMonthlyReport(PayrollRunItem item, MonthlyReport mr) {

        // What this employee's compensation scheme allows across this month.
        // Resolved once here and consulted below; null means unrestricted.
        PayrollSchemeScope scope = payrollSchemeScopeService.scopeFor(
                item.getEmployee() == null ? null : item.getEmployee().getId(),
                mr.getStartDate(), mr.getEndDate());

        // ── Operational totals from monthly report ────────────────────────────
        item.setTotalShiftMinutes(safe(mr.getTotalShiftMinutes()));
        item.setTotalWorkMinutes(safe(mr.getTotalWorkMinutes()));
        int paidAbsence   = safe(mr.getTotalAbsencePaidMinutes())   + safe(mr.getTotalSickLeavePaidMinutes());
        int unpaidAbsence = safe(mr.getTotalAbsenceUnpaidMinutes()) + safe(mr.getTotalSickLeaveUnpaidMinutes());
        item.setTotalAbsenceMinutes(paidAbsence + unpaidAbsence);
        item.setTotalPaidAbsenceMinutes(paidAbsence);
        item.setTotalUnpaidAbsenceMinutes(unpaidAbsence);
        item.setTotalCompensatedMinutes(0);
        item.setTotalApprovedMinutes(safe(mr.getTotalApprovedMinutes()));
        item.setTotalQuantity(safe(mr.getTotalQuantity()));
        item.setTotalScrap(safe(mr.getTotalScrap()));

        BigDecimal effectiveMinutes = mr.getTotalWeightedNormMinutes() != null
                ? mr.getTotalWeightedNormMinutes() : BigDecimal.ZERO;
        item.setTotalEffectiveMinutes(effectiveMinutes);

        // ── Performance metrics from monthly report ───────────────────────────
        item.setPerformanceRate(mr.getPerformanceRate());
        item.setApprovedPerformanceRate(mr.getApprovedPerformanceRate());
        item.setPerformanceCoefficient(mr.getPerformanceCoefficient());

        // ── Hourly rate: refresh system rate from employee ────────────────────
        if (item.getEmployee() != null && item.getEmployee().getHourlyRate() != null) {
            BigDecimal employeeRate = item.getEmployee().getHourlyRate();
            item.setHourlyRateSystem(employeeRate);
            if (!Boolean.TRUE.equals(item.getHourlyRateOverridden())) {
                item.setHourlyRate(employeeRate);
            }
        }

        // ── Payroll minutes ───────────────────────────────────────────────────
        int manualAdj = item.getManualAdjustedMinutes() != null ? item.getManualAdjustedMinutes() : 0;
        item.setTotalPayrollMinutes(safe(mr.getTotalWorkMinutes()) + manualAdj);

        // ── Meal allowance count + recalc total ───────────────────────────────
        //
        // Zeroed here, not merely hidden. totalNetEarnings adds
        // item.totalMealAllowanceAmount DIRECTLY, not through the adjustment
        // line, so suppressing only the adjustment row would remove the line
        // from the payslip while still paying the money.
        OffsetDateTime now = OffsetDateTime.now();
        boolean mealAllowed = allowsAdjustmentCode(scope, "MEAL_ALLOWANCE");
        int mealCount = !mealAllowed ? 0
                : (mr.getMealAllowanceNum() != null ? mr.getMealAllowanceNum() : 0);
        item.setMealAllowanceCount(mealCount);

        BigDecimal mealSystemRate = appSettingService.getMealAllowancePerDay(now);
        item.setMealAllowanceUnitAmountSystem(mealSystemRate);
        if (!Boolean.TRUE.equals(item.getMealAllowanceUnitAmountOverridden())) {
            item.setMealAllowanceUnitAmount(mealSystemRate);
        }
        BigDecimal mealUnitAmt = item.getMealAllowanceUnitAmount() != null
                ? item.getMealAllowanceUnitAmount() : BigDecimal.ZERO;
        BigDecimal totalMeal = mealUnitAmt.multiply(BigDecimal.valueOf(mealCount)).setScale(2, RoundingMode.HALF_UP);
        item.setTotalMealAllowanceAmount(totalMeal);
        updateAdjustmentByCategoryCode(item.getId(), "MEAL_ALLOWANCE", totalMeal, true);

        // ── Transport allowance ───────────────────────────────────────────────
        // Same reasoning as the meal allowance above: the item column feeds the
        // total directly, so it has to be zeroed and not just left unlinked.
        boolean transportAllowed = allowsAdjustmentCode(scope, CAT_CODE_TRANSPORT);
        BigDecimal transportSystemRate = transportAllowed
                ? appSettingService.getTransportAllowancePerDay(now)
                : BigDecimal.ZERO;
        item.setTransportAllowanceUnitAmount(transportSystemRate);
        int transportDays = !transportAllowed ? 0
                : (item.getTransportAllowanceDays() != null ? item.getTransportAllowanceDays() : 0);
        BigDecimal totalTransport = transportSystemRate
                .multiply(BigDecimal.valueOf(transportDays)).setScale(2, RoundingMode.HALF_UP);
        item.setTotalTransportAllowanceAmountSystem(totalTransport);
        if (!Boolean.TRUE.equals(item.getTotalTransportAllowanceAmountOverridden())) {
            item.setTotalTransportAllowanceAmount(totalTransport);
            updateAdjustmentByCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE", totalTransport, true);
        }

        // ── previousNetPayableAmount — from previous month's item for this employee ──
        if (item.getPeriod() != null && item.getEmployee() != null) {
            LocalDate prevPeriod = item.getPeriod().minusMonths(1).withDayOfMonth(1);
            payrollRunItemRepository.findByEmployee_IdAndPeriod(item.getEmployee().getId(), prevPeriod)
                    .stream().findFirst()
                    .ifPresent(prev -> item.setPreviousNetPayableAmount(prev.getNetPayableAmount()));
        }

        // ── Populate item categories from monthly report categories ───────────
        populateItemCategoriesFromMonthlyReport(item, mr, scope);

        // ── Recalculate summary totals from adjustment lines ──────────────────
        recalculateSummaryTotals(item);

        // ── Version stamp ─────────────────────────────────────────────────────
        item.setBasedOnVersion(mr.getVersion());
        item.setNeedsRecalculation(false);
        item.setLastCalculatedAt(LocalDateTime.now());
        item.setUpdatedAt(OffsetDateTime.now());

        log.info("PayrollRunItem {} recalculated from monthly_report id={} version={}",
                item.getId(), mr.getId(), mr.getVersion());

        return payrollRunItemRepository.save(item);
    }

    /**
     * After a user-initiated patch, propagates relevant changes to the next month's
     * unlocked item for the same employee:
     * <ul>
     *   <li>{@code PHONE_PREVIOUS_MONTH} adjustment ← current item's {@code currentMonthTelephone}</li>
     *   <li>{@code previousNetPayableAmount}         ← current item's {@code netPayableAmount}</li>
     * </ul>
     * Runs {@link #recalculateSummaryTotals} on the next item after updating.
     */
    private void propagateToNextMonthItem(PayrollRunItem item) {
        if (item.getPeriod() == null || item.getEmployee() == null) return;

        LocalDate nextPeriod = item.getPeriod().plusMonths(1).withDayOfMonth(1);
        payrollRunItemRepository
                .findUnlockedByEmployee_IdAndPeriod(item.getEmployee().getId(), nextPeriod)
                .stream().findFirst()
                .ifPresent(next -> {
                    boolean nextDirty = false;

                    // Update PHONE_PREVIOUS_MONTH adjustment
                    BigDecimal phone = item.getCurrentMonthTelephone() != null
                            ? item.getCurrentMonthTelephone() : BigDecimal.ZERO;
                    final BigDecimal phoneFinal = phone;
                    payrollAdjustmentRepository
                            .findByItemIdAndCategoryCode(next.getId(), "PHONE_PREVIOUS_MONTH")
                            .ifPresent(adj -> {
                                adj.setAmount(phoneFinal);
                                adj.setSystemAmount(phoneFinal);
                                adj.setUpdatedAt(OffsetDateTime.now());
                                payrollAdjustmentRepository.save(adj);
                            });

                    // Update previousNetPayableAmount
                    if (item.getNetPayableAmount() != null) {
                        next.setPreviousNetPayableAmount(item.getNetPayableAmount());
                        nextDirty = true;
                    }

                    if (nextDirty) {
                        recalculateSummaryTotals(next);
                        next.setUpdatedAt(OffsetDateTime.now());
                        payrollRunItemRepository.save(next);
                        log.info("Propagated phone/netPayable from PayrollRunItem {} to next-month item {}",
                                item.getId(), next.getId());
                    }
                });
    }

    /**
     * Recalculates all financial summary fields from scratch:
     * <ul>
     *   <li>totalNetEarnings = SUM(categories.amount) + SUM(applied ADDITIONS adj)</li>
     *   <li>totalDeductionsAmount = SUM(applied DEDUCTION_MINUS adj)  [display only]</li>
     *   <li>previouslyPaidAmount  = SUM(applied SETTLEMENTS adj)</li>
     *   <li>currentBalanceAmount  = totalNetEarnings - previouslyPaidAmount</li>
     *   <li>previousNetPayableAmount — must already be set on the item (set at init / month-recalc)</li>
     *   <li>netPayableAmount       = previousNetPayableAmount + currentBalanceAmount</li>
     * </ul>
     */
    public void recalculateSummaryTotals(PayrollRunItem item) {
        List<PayrollAdjustment> adjustments = payrollAdjustmentRepository
                .findByPayrollRunItemIdWithCategory(item.getId());

        List<PayrollRunItemCategory> categories = payrollRunItemCategoryRepository
                .findByPayrollRunItemIdWithWorkCodeCategory(item.getId());

        // ── totalNetEarnings ──────────────────────────────────────────────────
        BigDecimal categoriesSum = categories.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Meal and transport are taken directly from item fields (always up-to-date).
        // Their corresponding adjustments (MEAL_ALLOWANCE, TRANSPORT_ALLOWANCE) are
        // excluded from additionsSum to avoid double-counting.
        BigDecimal additionsSum = adjustments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && SECTION_ADDITIONS.equalsIgnoreCase(
                                a.getPayrollAdjustmentCategory().getSectionCode())
                        && !"MEAL_ALLOWANCE".equals(a.getPayrollAdjustmentCategory().getCode())
                        && !"TRANSPORT_ALLOWANCE".equals(a.getPayrollAdjustmentCategory().getCode()))
                .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal meal      = item.getTotalMealAllowanceAmount()      != null ? item.getTotalMealAllowanceAmount()      : BigDecimal.ZERO;
        BigDecimal transport = item.getTotalTransportAllowanceAmount()  != null ? item.getTotalTransportAllowanceAmount()  : BigDecimal.ZERO;

        BigDecimal totalNetEarnings = categoriesSum
                .add(meal)
                .add(transport)
                .add(additionsSum)
                .setScale(2, RoundingMode.HALF_UP);
        item.setTotalNetEarnings(totalNetEarnings);

        // ── totalDeductionsAmount (kept for display) ──────────────────────────
        BigDecimal deductions = adjustments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && DEDUCTION_MINUS.equals(a.getPayrollAdjustmentCategory().getImpactCode()))
                .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        item.setTotalDeductionsAmount(deductions.setScale(2, RoundingMode.HALF_UP));

        // ── previouslyPaidAmount ──────────────────────────────────────────────
        BigDecimal previouslyPaid = adjustments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && SECTION_SETTLEMENTS.equalsIgnoreCase(
                                a.getPayrollAdjustmentCategory().getSectionCode()))
                .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        item.setPreviouslyPaidAmount(previouslyPaid);

        // ── currentBalanceAmount ──────────────────────────────────────────────
        BigDecimal currentBalance = totalNetEarnings.subtract(previouslyPaid).setScale(2, RoundingMode.HALF_UP);
        item.setCurrentBalanceAmount(currentBalance);

        // ── netPayableAmount ──────────────────────────────────────────────────
        BigDecimal prevNetPayable = item.getPreviousNetPayableAmount() != null
                ? item.getPreviousNetPayableAmount() : BigDecimal.ZERO;
        item.setNetPayableAmount(prevNetPayable.add(currentBalance).setScale(2, RoundingMode.HALF_UP));
    }

    /** Re-fetches with the monthly report joined and recalculates if version is stale or needs_recalculation is set. */
    private PayrollRunItem refreshIfStale(PayrollRunItem item) {
        return payrollRunItemRepository.findByIdWithMonthlyReport(item.getId())
                .map(fresh -> {
                    MonthlyReport mr = fresh.getMonthlyReport();
                    if (mr == null) return fresh;
                    Integer latest = mr.getVersion();
                    Integer used   = fresh.getBasedOnVersion();
                    boolean versionStale   = latest != null && !latest.equals(used);
                    boolean flaggedForRecalc = Boolean.TRUE.equals(fresh.getNeedsRecalculation());
                    if (!versionStale && !flaggedForRecalc) return fresh;
                    return recalculateFromMonthlyReport(fresh, mr);
                })
                .orElse(item);
    }

    private static int safe(Integer v) { return v != null ? v : 0; }

    private void initializeCreateDefaults(PayrollRunItem item) {
        if (item.getTotalShiftMinutes() == null)       item.setTotalShiftMinutes(0);
        if (item.getTotalWorkMinutes() == null)         item.setTotalWorkMinutes(0);
        if (item.getTotalAbsenceMinutes() == null)      item.setTotalAbsenceMinutes(0);
        if (item.getTotalPaidAbsenceMinutes() == null)  item.setTotalPaidAbsenceMinutes(0);
        if (item.getTotalUnpaidAbsenceMinutes() == null) item.setTotalUnpaidAbsenceMinutes(0);
        if (item.getTotalCompensatedMinutes() == null)  item.setTotalCompensatedMinutes(0);
        if (item.getTotalApprovedMinutes() == null)     item.setTotalApprovedMinutes(0);
        if (item.getTotalQuantity() == null)            item.setTotalQuantity(0);
        if (item.getTotalScrap() == null)               item.setTotalScrap(0);
        if (item.getTotalEffectiveMinutes() == null)    item.setTotalEffectiveMinutes(BigDecimal.ZERO);
        if (item.getTotalWorkDays() == null)            item.setTotalWorkDays(0);
        if (item.getTotalPaidDays() == null)            item.setTotalPaidDays(0);
        if (item.getTotalAbsenceDays() == null)         item.setTotalAbsenceDays(0);

        if (item.getManualAdjustedMinutes() == null)   item.setManualAdjustedMinutes(0);
        if (item.getTotalPayrollMinutes() == null)      item.setTotalPayrollMinutes(0);

        if (item.getStatus() == null)                   item.setStatus(STATUS_DRAFT);

        if (item.getHourlyRate() == null)               item.setHourlyRate(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getHourlyRateSystem() == null)         item.setHourlyRateSystem(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getHourlyRateOverridden() == null)     item.setHourlyRateOverridden(false);

        if (item.getBaseBonusAmountSystem() == null)    item.setBaseBonusAmountSystem(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getBaseBonusAmount() == null)          item.setBaseBonusAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getBaseBonusAmountOverridden() == null) item.setBaseBonusAmountOverridden(false);
        if (item.getBonusCorrectionAmountSystem() == null) item.setBonusCorrectionAmountSystem(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getBonusCorrectionAmount() == null)    item.setBonusCorrectionAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getBonusCorrectionAmountOverridden() == null) item.setBonusCorrectionAmountOverridden(false);
        if (item.getTotalBonusAmountSystem() == null)   item.setTotalBonusAmountSystem(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getTotalBonusAmount() == null)         item.setTotalBonusAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getTotalBonusAmountOverridden() == null) item.setTotalBonusAmountOverridden(false);

        if (item.getTotalGrossEarnings() == null)       item.setTotalGrossEarnings(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getTotalDeductionsAmount() == null)    item.setTotalDeductionsAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getCurrentMonthTelephone() == null)    item.setCurrentMonthTelephone(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getPreviouslyPaidAmount() == null)     item.setPreviouslyPaidAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        // previousNetPayableAmount is populated from the previous period — leave null on creation
        if (item.getCurrentBalanceAmount() == null)     item.setCurrentBalanceAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getNetPayableAmount() == null)         item.setNetPayableAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        if (item.getCurrencyCode() == null)             item.setCurrencyCode(DEFAULT_CURRENCY);
        if (item.getCalcVersion() == null)              item.setCalcVersion(DEFAULT_CALC_VERSION);
        if (item.getNeedsRecalculation() == null)       item.setNeedsRecalculation(false);

        if (item.getCreatedAt() == null)                item.setCreatedAt(OffsetDateTime.now());
    }
}
