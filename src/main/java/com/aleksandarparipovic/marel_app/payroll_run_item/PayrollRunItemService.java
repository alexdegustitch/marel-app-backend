package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollRunItemService {

    private static final String STATUS_LOCKED = "LOCKED";

    private final PayrollRunItemRepository payrollRunItemRepository;

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

    @Transactional
    public PayrollRunItem create(PayrollRunItem entity) {
        entity.setId(null);
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

        if (latestVersion != null && latestVersion.equals(usedVersion)) {
            log.debug("PayrollRunItem {} is up-to-date at version {}", id, latestVersion);
            return item;
        }

        log.info("PayrollRunItem {} is stale (based_on_version={}, monthly_report.version={}) – recalculating",
                id, usedVersion, latestVersion);
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

    // ─── Private helpers ────────────────────────────────────────────────────

    /**
     * Copies all aggregated fields from the monthly report into the payroll item,
     * recalculates derived financial amounts, and stamps {@code based_on_version}
     * with the current monthly report version.
     *
     * <p>This method is idempotent: calling it multiple times with the same
     * monthly report version produces the same result.
     */
    private PayrollRunItem recalculateFromMonthlyReport(PayrollRunItem item, MonthlyReport mr) {

        // ── Operational totals from monthly report ───────────────────────────
        item.setTotalShiftMinutes(     safe(mr.getTotalShiftMinutes()));
        item.setTotalWorkMinutes(      safe(mr.getTotalWorkMinutes()));
        item.setTotalAbsenceMinutes(   safe(mr.getTotalAbsenceMinutes()));
        item.setTotalPaidAbsenceMinutes(   safe(mr.getTotalPaidAbsenceMinutes()));
        item.setTotalUnpaidAbsenceMinutes( safe(mr.getTotalUnpaidAbsenceMinutes()));
        item.setTotalCompensatedMinutes(   safe(mr.getTotalCompensatedMinutes()));
        item.setTotalApprovedMinutes(  safe(mr.getTotalApprovedMinutes()));
        item.setTotalQuantity(         safe(mr.getTotalQuantity()));
        item.setTotalScrap(            safe(mr.getTotalScrap()));

        BigDecimal effectiveMinutes = mr.getTotalEffectiveMinutes() != null
                ? mr.getTotalEffectiveMinutes() : BigDecimal.ZERO;
        item.setTotalEffectiveMinutes(effectiveMinutes);

        // ── Meal allowance ───────────────────────────────────────────────────
        boolean removeMeal = Boolean.TRUE.equals(item.getRemoveMealAllowance());
        int mealNum = removeMeal ? 0 : safe(mr.getMealAllowanceNum());
        item.setMealAllowanceNum(mealNum);

        BigDecimal mealAmount = item.getMealAllowanceAmount() != null
                ? item.getMealAllowanceAmount() : BigDecimal.ZERO;
        item.setTotalMealAllowance(
                BigDecimal.valueOf(mealNum).multiply(mealAmount).setScale(2, RoundingMode.HALF_UP));

        // ── Transport allowance ──────────────────────────────────────────────
        boolean removeTransport = Boolean.TRUE.equals(item.getRemoveTransportAllowance());
        int transportNum = removeTransport ? 0 : safe(mr.getTotalApprovedMinutes()) / 60; // one per worked day approx
        item.setTransportAllowanceNum(transportNum);

        BigDecimal transportAmount = item.getTransportAllowanceAmount() != null
                ? item.getTransportAllowanceAmount() : BigDecimal.ZERO;
        item.setTotalTransportAllowance(
                BigDecimal.valueOf(transportNum).multiply(transportAmount).setScale(2, RoundingMode.HALF_UP));

        // ── Base pay from effective hours × hourly rate ──────────────────────
        BigDecimal hourlyRate = item.getHourlyRate() != null ? item.getHourlyRate() : BigDecimal.ZERO;
        BigDecimal effectiveHours = effectiveMinutes.divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        BigDecimal baseAmount = effectiveHours.multiply(hourlyRate).setScale(2, RoundingMode.HALF_UP);
        item.setBaseAmount(baseAmount);

        // ── Gross = base + bonus + adjustments ──────────────────────────────
        BigDecimal bonus      = item.getBonusAmount()      != null ? item.getBonusAmount()      : BigDecimal.ZERO;
        BigDecimal adjustment = item.getAdjustmentAmount() != null ? item.getAdjustmentAmount() : BigDecimal.ZERO;
        BigDecimal gross = baseAmount.add(bonus).add(adjustment).setScale(2, RoundingMode.HALF_UP);
        item.setTotalGrossAmount(gross);

        // Net = gross (tax/deductions are managed separately at a higher layer)
        item.setTotalNetAmount(gross);

        // ── Version stamp ────────────────────────────────────────────────────
        item.setBasedOnVersion(mr.getVersion());
        item.setLastCalculatedAt(LocalDateTime.now());
        item.setUpdatedAt(OffsetDateTime.now());

        log.info("PayrollRunItem {} recalculated from monthly_report id={} version={}",
                item.getId(), mr.getId(), mr.getVersion());

        return payrollRunItemRepository.save(item);
    }

    /** Re-fetches with the monthly report joined and recalculates if version is stale. */
    private PayrollRunItem refreshIfStale(PayrollRunItem item) {
        return payrollRunItemRepository.findByIdWithMonthlyReport(item.getId())
                .map(fresh -> {
                    MonthlyReport mr = fresh.getMonthlyReport();
                    if (mr == null) return fresh;
                    Integer latest = mr.getVersion();
                    Integer used   = fresh.getBasedOnVersion();
                    if (latest != null && latest.equals(used)) return fresh;
                    return recalculateFromMonthlyReport(fresh, mr);
                })
                .orElse(item);
    }

    private static int safe(Integer v) { return v != null ? v : 0; }
}
