package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.payroll_calculation.CalculationKeys;
import com.aleksandarparipovic.marel_app.payroll_calculation.calculators.MonthlyBonusCalculator;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentContext;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentResult;
import com.aleksandarparipovic.marel_app.payroll_calculation.PayrollCalculatorRegistry;
import com.aleksandarparipovic.marel_app.payroll_calculation.calculators.MealAllowanceCalculator;
import com.aleksandarparipovic.marel_app.payroll_calculation.calculators.TransportAllowanceCalculator;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueService;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryNameResolver;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.work_category_resolution.EffectiveComponentConfig;
import com.aleksandarparipovic.marel_app.work_category_resolution.IncompletePayrollConfigurationException;
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
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustment;
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustmentRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.RecentPayrollSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemHandoverDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemActivityDto;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollRunItemService {

    private static final String STATUS_LOCKED = "LOCKED";
    private static final String STATUS_DRAFT = "DRAFT";
    /** "Spreman" — handed over by the shop floor, not yet frozen by payroll. */
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String DEFAULT_CURRENCY = "RSD";
    private static final int DEFAULT_CALC_VERSION = 1;

    // impact codes
    private static final String IMPACT_GROSS_PLUS = "GROSS_PLUS";

    private final PayrollRunItemRepository payrollRunItemRepository;
    private final PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;
    private final PayrollAdjustmentRepository payrollAdjustmentRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final MonthlyReportCategoryRepository monthlyReportCategoryRepository;
    private final AppSettingService appSettingService;
    private final EntityReferenceProvider referenceProvider;
    private final CurrentUserService currentUserService;
    private final com.aleksandarparipovic.marel_app.payroll_run.PayrollVisibilityPolicy payrollVisibilityPolicy;
    private final PayrollRunItemHandoverRepository handoverRepository;
    private final com.aleksandarparipovic.marel_app.user.UserRepository userRepository;
    private final com.aleksandarparipovic.marel_app.employee_payroll_run_item_update.EmployeePayrollRunItemUpdateService payrollRunItemUpdateService;
    private final WorkCodeCategoryNameResolver workCodeCategoryNameResolver;
    private final PayrollAdjustmentCategoryNameResolver payrollAdjustmentCategoryNameResolver;
    private final PayrollAdjustmentCategoryRepository payrollAdjustmentCategoryRepository;
    private final PayrollTimeAdjustmentRepository timeAdjustmentRepository;
    private final PayrollTimeAdjustmentCategoryRepository timeAdjustmentCategoryRepository;
    private final PayrollSchemeScopeService payrollSchemeScopeService;
    private final EmployeePayrollValueService employeePayrollValueService;
    private final PayrollCalculatorRegistry calculatorRegistry;
    private final com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository workCodeCategoryRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    // ─── Standard CRUD ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PayrollRunItem> findAll() {
        return withCorrections(payrollRunItemRepository.findAll());
    }

    @Transactional(readOnly = true)
    public PayrollRunItem findById(Long id) {
        return withCorrection(payrollRunItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found")));
    }

    /**
     * Fill the derived minute correction on an item about to leave the service.
     *
     * <p>manual_adjusted_minutes was a column written from payroll_time_adjustments
     * on every save. The rows are the record — one per cause, each with its reason
     * and its own audit trail — and the column was a second copy that only stayed
     * right because every writer remembered to keep it right. This reads it where
     * it lives, at the moment it is needed.
     */
    private PayrollRunItem withCorrection(PayrollRunItem item) {
        if (item != null) {
            item.setManualAdjustedMinutes(payableMinuteCorrectionFor(item.getId()));
        }
        return item;
    }

    /**
     * The same for a whole list, in ONE query.
     *
     * <p>Per-item would be a query per row of a payroll run — the shape
     * PayrollSchemeScopeBatchingIT exists to keep out of this class.
     */
    private List<PayrollRunItem> withCorrections(List<PayrollRunItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, Integer> byItem = new HashMap<>();
        timeAdjustmentRepository.sumPayableMinutesByItem(
                        items.stream().map(PayrollRunItem::getId).filter(Objects::nonNull).toList())
                .forEach(row -> byItem.put((Long) row[0], ((Number) row[1]).intValue()));

        // Absent means no correction: GROUP BY returns no row for an item with
        // none, and 0 is what "nothing was corrected" reads as on screen.
        items.forEach(item -> item.setManualAdjustedMinutes(byItem.getOrDefault(item.getId(), 0)));
        return items;
    }

    @Transactional(readOnly = true)
    public List<RecentPayrollSummaryDto> getRecentByEmployee(Long employeeId, int size) {
        return payrollRunItemRepository.findRecentByEmployeeId(employeeId, PageRequest.of(0, size))
                .stream()
                .map(RecentPayrollSummaryDto::new)
                // Same rule as the payroll list: the employee page shows the
                // very same figure, so hiding it in one place only would be a
                // curtain rather than a permission.
                .map(dto -> payrollVisibilityPolicy.canSeeAmounts()
                        ? dto
                        : new RecentPayrollSummaryDto(
                                dto.id(), dto.monthlyReportId(), dto.period(),
                                payrollVisibilityPolicy.visibleStatus(dto.status()), null, null))
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
    /** Mark every unlocked, unarchived item stale. Its own transaction, on purpose. */
    @Transactional
    public int flagAllForRecalculation() {
        return payrollRunItemRepository.flagAllForRecalculation();
    }

    @Transactional(readOnly = true)
    public List<Long> recalculableItemIds() {
        return payrollRunItemRepository.findAllRecalculableIds();
    }

    @Transactional
    public PayrollRunItem getForPayrollAccess(Long id) {
        // Wrapped rather than filled at each of the four exits below — one of them
        // is easy to add and forget, and then one screen shows the correction and
        // another shows nothing.
        return withCorrection(loadForPayrollAccess(id));
    }

    private PayrollRunItem loadForPayrollAccess(Long id) {
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

        // Same reasoning as in refreshIfStale, which keeps its own copy of this
        // check: the monthly report's version guards the AMOUNTS, not the ROW
        // SET. The row set also follows the employee's compensation scheme, and
        // changing that does not touch the monthly report — so an item refreshed
        // before a scheme change would look up to date forever and never grow the
        // row its work now lands on.
        // Resolved ONCE here and handed to both steps. Both need it, and both used
        // to resolve it for themselves — four queries where two do, multiplied by
        // every row of a payroll run.
        PayrollSchemeScope scope = scopeFor(item);

        boolean addedRowWithActivity = reconcileItemCategories(item, mr, scope);

        Integer latestVersion = mr.getVersion();
        Integer usedVersion   = item.getBasedOnVersion();
        boolean versionStale  = latestVersion != null && !latestVersion.equals(usedVersion);
        boolean flaggedForRecalc = Boolean.TRUE.equals(item.getNeedsRecalculation());

        if (!versionStale && !flaggedForRecalc && !addedRowWithActivity) {
            log.debug("PayrollRunItem {} is up-to-date at version {}", id, latestVersion);
            return item;
        }

        if (flaggedForRecalc) {
            log.info("PayrollRunItem {} is flagged for recalculation (needs_recalculation=true) – recalculating", id);
        } else {
            log.info("PayrollRunItem {} is stale (based_on_version={}, monthly_report.version={}) – recalculating",
                    id, usedVersion, latestVersion);
        }
        return recalculateFromMonthlyReport(item, mr, scope);
    }

    /**
     * Convenience lookup by payroll run + employee with the same version-check semantics.
     * Creates a new item skeleton if none exists yet for this run/employee combination.
     */
    @Transactional
    public List<PayrollRunItem> getForPayrollRun(Long payrollRunId) {
        List<PayrollRunItem> items = payrollRunItemRepository.findByPayrollRun_Id(payrollRunId);
        if (items.isEmpty()) {
            return items;
        }
        ScopeSource scopes = scopeSourceFor(items);
        return withCorrections(items.stream()
                .map(item -> STATUS_LOCKED.equals(item.getStatus()) ? item : refreshIfStale(item, scopes))
                .toList());
    }

    /**
     * Where a scope comes from for one item.
     *
     * <p>Two implementations, one meaning. A payroll run resolves the whole batch
     * up front; anything else resolves per item. Kept as an interface rather than
     * a nullable map so that "no batch was possible" degrades to the old
     * per-item resolution instead of to the very different "unrestricted".
     */
    private interface ScopeSource {
        PayrollSchemeScope scopeOf(PayrollRunItem item);
    }

    /**
     * One scheme resolution for a whole payroll run.
     *
     * <p>Every item in a run belongs to the same month, and a factory has a
     * handful of schemes against hundreds of employees, so this is a fixed number
     * of queries instead of four per row. Same reasoning as
     * {@link PayrollSchemeScopeService#scopesFor}, which is built for it.
     */
    private ScopeSource scopeSourceFor(List<PayrollRunItem> items) {
        LocalDate anyPeriod = items.stream()
                .map(PayrollRunItem::getPeriod)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (anyPeriod == null) {
            // Nothing to batch on. Fall back to exactly what happened before.
            return this::scopeFor;
        }

        LocalDate start = anyPeriod.withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        List<Long> employeeIds = items.stream()
                .map(PayrollRunItem::getEmployee)
                .filter(java.util.Objects::nonNull)
                .map(Employee::getId)
                .distinct()
                .toList();

        Map<Long, PayrollSchemeScope> byEmployee = payrollSchemeScopeService.scopesFor(
                employeeIds, start, end,
                workCodeCategoryRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByDisplayOrderAscIdAsc(),
                payrollAdjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull());

        // Absent from the map means no scheme period covers the month, which
        // callers read as unrestricted — the same answer scopeFor gives.
        return item -> item.getEmployee() == null ? null : byEmployee.get(item.getEmployee().getId());
    }

    // ─── Locking ────────────────────────────────────────────────────────────

    /**
     * Freeze one payroll item so nothing recalculates it again.
     *
     * <p>Until now nothing in the codebase ever did this. {@code getForPayrollAccess}
     * honoured a LOCKED status and the permissions DTO reported {@code canLock},
     * but no code path set it — so every payroll month back to 2023 stayed exposed
     * to the next change in the calculation, and D8's "a required manual line
     * blocks locking" had nothing to block.
     *
     * <p>Locking is what makes a calculated month a RECORD rather than a view: from
     * here on the amounts are what was paid, whatever the rules become later.
     *
     * @throws ConflictException when a required manual line has no input, listing
     *         each one. A month cannot be frozen while somebody still owes it a
     *         number — the alternative is freezing a zero that was never a decision.
     */
    @Transactional
    public PayrollRunItem lock(Long id) {
        markHumanDecision(id);
        PayrollRunItem item = payrollRunItemRepository.findByIdWithMonthlyReport(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found: " + id));

        if (STATUS_LOCKED.equals(item.getStatus())) {
            return item;
        }

        // The chain is DRAFT -> APPROVED -> LOCKED. Payroll freezes what the shop
        // floor handed over; freezing a draft would make permanent a month nobody
        // has said is finished.
        if (!STATUS_APPROVED.equals(item.getStatus())) {
            throw new ConflictException(
                    "Obračun se zaključava tek kada bude predat. Trenutno stanje: "
                            + item.getStatus() + ".");
        }

        // Recalculate first. Locking a stale item would freeze figures that were
        // already out of date at the moment they became permanent.
        item = getForPayrollAccess(id);

        List<String> pending = pendingRequiredInputs(item);
        if (!pending.isEmpty()) {
            throw new ConflictException(
                    "Obračun se ne može zaključati dok se ne unesu obavezne stavke: "
                            + String.join(", ", pending) + ".");
        }

        item.setStatus(STATUS_LOCKED);
        item.setLockedAt(OffsetDateTime.now());
        item.setLockedBy(currentUserService.getCurrentUserId());
        item.setUpdatedAt(OffsetDateTime.now());

        log.info("PayrollRunItem {} locked by user {}", id, item.getLockedBy());
        return payrollRunItemRepository.save(item);
    }

    /**
     * Required manual lines nobody has filled in yet.
     *
     * <p>"Not entered" and "entered as 0" are different, and only
     * {@code has_manual_input} can tell them apart — an amount of zero is a
     * perfectly good answer once somebody has actually given it. Without the flag a
     * required line would either block forever or never block at all.
     */
    @Transactional(readOnly = true)
    public List<String> pendingRequiredInputs(PayrollRunItem item) {
        PayrollSchemeScope scope = scopeFor(item);

        return payrollAdjustmentRepository.findByPayrollRunItemIdWithCategory(item.getId()).stream()
                .filter(a -> {
                    EffectiveComponentConfig config =
                            scope.componentConfig(a.getPayrollAdjustmentCategory().getId());
                    return config != null && config.allowed() && config.requiredManualInput();
                })
                .filter(a -> !Boolean.TRUE.equals(a.getHasManualInput()))
                .map(a -> a.getPayrollAdjustmentCategory().getCode())
                .sorted()
                .toList();
    }

    /**
     * Hand the month over to payroll: DRAFT → APPROVED, "spreman".
     *
     * <p>Recalculates and checks required inputs FIRST, for the same reason
     * {@link #lock} does: the figures recorded as "what was handed over" have to
     * be the ones that were actually true, not ones that went stale before
     * anybody looked. Handing over a stale month would put a number into the
     * audit record that never existed.
     *
     * <p>Idempotent — handing over an already-handed-over month is not an error
     * and does not add a second row, otherwise a double-clicked button would
     * invent a handover that never happened.
     */
    @Transactional
    public PayrollRunItem submit(Long id, String note) {
        markHumanDecision(id);
        PayrollRunItem item = payrollRunItemRepository.findByIdWithMonthlyReport(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found: " + id));

        if (STATUS_APPROVED.equals(item.getStatus())) {
            return item;
        }
        if (STATUS_LOCKED.equals(item.getStatus())) {
            throw new ConflictException("Zaključan obračun se ne može ponovo predati.");
        }

        item = getForPayrollAccess(id);

        List<String> pending = pendingRequiredInputs(item);
        if (!pending.isEmpty()) {
            throw new ConflictException(
                    "Obračun se ne može predati dok se ne unesu obavezne stavke: "
                            + String.join(", ", pending) + ".");
        }

        String before = item.getStatus();
        item.setStatus(STATUS_APPROVED);
        item.setUpdatedAt(OffsetDateTime.now());
        PayrollRunItem saved = payrollRunItemRepository.save(item);

        recordHandover(saved, PayrollRunItemHandover.EVENT_SUBMITTED, before, STATUS_APPROVED, note);
        log.info("PayrollRunItem {} submitted by user {}", id, currentUserService.getCurrentUserId());
        return saved;
    }

    /**
     * Send it back for correction: APPROVED → DRAFT.
     *
     * <p>The inverse of {@link #submit} and a row of its own, so the sequence
     * "handed over, returned, handed over again" survives. It does NOT erase the
     * earlier handover — that record is what a later argument is settled with.
     */
    @Transactional
    public PayrollRunItem returnToDraft(Long id, String note) {
        markHumanDecision(id);
        PayrollRunItem item = payrollRunItemRepository.findByIdWithMonthlyReport(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found: " + id));

        if (STATUS_DRAFT.equals(item.getStatus())) {
            return item;
        }
        if (!STATUS_APPROVED.equals(item.getStatus())) {
            throw new ConflictException(
                    "Na doradu se vraća samo predat obračun. Trenutno stanje: "
                            + item.getStatus() + ".");
        }

        item.setStatus(STATUS_DRAFT);
        item.setNeedsRecalculation(true);
        item.setUpdatedAt(OffsetDateTime.now());
        PayrollRunItem saved = payrollRunItemRepository.save(item);

        recordHandover(saved, PayrollRunItemHandover.EVENT_RETURNED, STATUS_APPROVED, STATUS_DRAFT, note);
        log.info("PayrollRunItem {} returned to draft by user {}", id, currentUserService.getCurrentUserId());
        return saved;
    }

    /**
     * Every handover step, newest first.
     *
     * <p>The same visibility rule as the live payroll applies here: a stored
     * record of an amount is still the amount, so a reader who may not see what
     * a month is worth does not get it back through its history.
     */
    @Transactional(readOnly = true)
    public List<PayrollRunItemHandoverDto> getHandovers(Long payrollRunItemId) {
        boolean amounts = payrollVisibilityPolicy.canSeeAmounts();
        return handoverRepository.findByPayrollRunItemIdOrderByOccurredAtDesc(payrollRunItemId).stream()
                .map(h -> new PayrollRunItemHandoverDto(
                        h.getId(),
                        h.getEvent(),
                        h.getActorId(),
                        actorName(h.getActorId()),
                        h.getOccurredAt(),
                        h.getStatusBefore(),
                        h.getStatusAfter(),
                        amounts ? h.getTotalNetEarnings() : null,
                        amounts ? h.getNetPayableAmount() : null,
                        h.getNote()))
                .toList();
    }

    /** Who did it, for the screen. Null id or a removed user reads as unknown. */
    private String actorName(Long actorId) {
        if (actorId == null) return null;
        return userRepository.findById(actorId)
                .map(com.aleksandarparipovic.marel_app.user.User::getFullName)
                .orElse(null);
    }

    /**
     * Append one step, with the figures as they stand at this instant.
     *
     * <p>The totals are copied rather than referenced: the item keeps moving
     * afterwards, and the whole point of the row is to say what it said now.
     */
    private void recordHandover(PayrollRunItem item, String event, String before, String after, String note) {
        handoverRepository.save(PayrollRunItemHandover.builder()
                .payrollRunItemId(item.getId())
                .event(event)
                .actorId(currentUserService.getCurrentUserId())
                .occurredAt(OffsetDateTime.now())
                .statusBefore(before)
                .statusAfter(after)
                .totalNetEarnings(item.getTotalNetEarnings())
                .netPayableAmount(item.getNetPayableAmount())
                .note(note == null || note.isBlank() ? null : note.trim())
                .build());
    }

    /** Undo a lock. Separate operation, separate permission, separate audit entry. */
    @Transactional
    public PayrollRunItem unlock(Long id) {
        markHumanDecision(id);
        PayrollRunItem item = payrollRunItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PayrollRunItem not found: " + id));

        if (!STATUS_LOCKED.equals(item.getStatus())) {
            return item;
        }

        // Back to APPROVED, not DRAFT: unlocking undoes exactly one step, the lock.
        // Dropping to DRAFT would also silently undo the supervisor's handover,
        // which is a different decision with a different owner — that is what
        // returnToDraft is for.
        //
        // needs_recalculation, not a recalculation here: unlocking says the month is
        // open again, and what it should now say is decided by whoever opens it.
        item.setStatus(STATUS_APPROVED);
        item.setLockedAt(null);
        item.setLockedBy(null);
        item.setNeedsRecalculation(true);
        item.setUpdatedAt(OffsetDateTime.now());

        log.info("PayrollRunItem {} unlocked by user {}", id, currentUserService.getCurrentUserId());
        return payrollRunItemRepository.save(item);
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

        // Only what pertains to this employee. Rows a run was initialised with
        // before the employee moved onto a restricted scheme are filtered out
        // here rather than deleted, so the payslip is right immediately and the
        // data stays recoverable.
        //
        // ANYTHING WITH ACTIVITY IS ALWAYS SHOWN, even if the scheme now excludes
        // it. Hiding recorded work would turn a display rule into missing money
        // on a document somebody is paid from. Only empty rows are dropped.
        PayrollSchemeScope scope = scopeFor(item);

        List<PayrollRunItemCategoryDetailDto> categories =
                payrollRunItemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(item.getId())
                        .stream()
                        .filter(c -> scope.allowsWorkCategory(c.getWorkCodeCategory().getId())
                                || hasActivity(c))
                        .map(c -> new PayrollRunItemCategoryDetailDto(c, workCodeNames))
                        .toList();

        // Each line carries the SCHEME's resolved answer — visible, editable,
        // required, forced to zero — so the client renders from data instead of
        // knowing what a foreign or commercial employee is. Lines the scheme
        // excludes are dropped here; everything else arrives with its own policy
        // attached, including the ones that are visible and always zero.
        List<PayrollAdjustmentSectionDto> adjustments =
                payrollAdjustmentRepository.findByPayrollRunItemIdWithCategory(item.getId())
                        .stream()
                        .filter(a -> scope.allowsAdjustmentCategory(
                                a.getPayrollAdjustmentCategory().getId()))
                        .map(a -> new PayrollAdjustmentDetailDto(a, adjustmentNames,
                                scope.componentConfig(a.getPayrollAdjustmentCategory().getId())))
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

        PayrollRunItemResponse summary = new PayrollRunItemResponse(item);
        // Resolved at the period's LAST day, matching how MonthlyRecalcService caps
        // approved_performance_rate. Reading it at now() would draw an old month
        // against today's ceiling.
        if (item.getPeriod() != null) {
            summary.setMaxEfficiencyPercent(appSettingService.getMaxEfficiencyPercentOn(
                    item.getPeriod().withDayOfMonth(item.getPeriod().lengthOfMonth())));
        }

        return new PayrollRunItemDetailResponse(
                summary,
                categories,
                adjustments,
                permissions,
                locale
        );
    }

    /**
     * The employee's hourly rate on {@code pricingDate}, or {@code null} when
     * neither source has one.
     *
     * <p>{@code null} means "not configured", and the caller leaves the existing
     * system rate alone. It deliberately does NOT mean zero: an employee with no
     * rate is calculated at whatever the item already carries, which for most of
     * this database is 0 and must stay 0.
     */
    private BigDecimal hourlyRateFor(PayrollRunItem item, LocalDate pricingDate) {
        if (item.getEmployee() == null) {
            return null;
        }
        Long employeeId = item.getEmployee().getId();

        Optional<BigDecimal> fromHistory = employeePayrollValueService.numericValueOn(
                employeeId, EmployeePayrollValueCodes.HOURLY_RATE, pricingDate);
        if (fromHistory.isPresent()) {
            return fromHistory.get();
        }

        BigDecimal fromEmployee = item.getEmployee().getHourlyRate();
        if (fromEmployee != null) {
            log.debug("Employee {} has no HOURLY_RATE in force on {} — falling back to "
                    + "employees.hourly_rate, which is not period-correct", employeeId, pricingDate);
        }
        return fromEmployee;
    }

    /** True when a category row carries real work, whatever the scheme says today. */
    private boolean hasActivity(PayrollRunItemCategory category) {
        return safe(category.getTotalMinutes()) > 0
                || safe(category.getTotalQuantity()) > 0
                || (category.getAmount() != null && category.getAmount().compareTo(BigDecimal.ZERO) != 0);
    }

    /** The scheme scope for one item's own payroll month; null means unrestricted. */
    private PayrollSchemeScope scopeFor(PayrollRunItem item) {
        MonthlyReport mr = item.getMonthlyReport();
        if (mr == null || mr.getStartDate() == null || mr.getEndDate() == null || item.getEmployee() == null) {
            return null;
        }
        return payrollSchemeScopeService.scopeFor(
                item.getEmployee().getId(), mr.getStartDate(), mr.getEndDate());
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

        // LAYER B — once handed over, the shop floor is done with it. Payroll may
        // still correct an APPROVED month; the supervisor who submitted it may
        // not, because they have already said it is finished. Sending it back
        // (return-to-draft) is how they get it again.
        if (STATUS_APPROVED.equals(item.getStatus()) && payrollVisibilityPolicy.isRestrictedUser()) {
            throw new ConflictException(
                    "Obračun je predat i više se ne menja. Vratite ga na doradu da biste ga menjali.");
        }

        // LAYER A — what a caller may not SEE, they may not WRITE. The amounts are
        // already withheld from the response for these roles; accepting them on
        // the way in would let somebody set a figure they cannot read, which is a
        // worse hole than the one hiding it closed.
        refuseHiddenMoneyEdits(req);

        // ── 1. Simple fields (no cascade) ────────────────────────────────────
        if (req.getNote() != null) {
            item.setNote(req.getNote());
        }
        // The current month's phone is edited on its LINE, through the adjustments
        // array — PHONE_CURRENT_MONTH is a MANUAL category whose editable input IS
        // the amount. The column beside it is gone; the line is what next month's
        // initialisation reads to raise PHONE_PREVIOUS_MONTH.
        //
        // WHETHER IT REDUCES PAY IS STILL UNANSWERED (OPEN-12) and nothing here
        // changes it: PHONE_CURRENT_MONTH reaches no total today, exactly as
        // before. Where the figure is stored and what it does to the balance are
        // two different questions.
        refuseEditsToExcludedCategories(item, req);
        markHumanDecision(id);

        if (req.getManualAdjustedMinutes() != null) {
            // The correction is a ROW now, not an integer on the item. The column
            // is kept in step until it is dropped, exactly as the meal and
            // transport columns are.
            syncManualTimeCorrection(item, req.getManualAdjustedMinutes(),
                    req.getManualAdjustedMinutesReason());
            int base = item.getTotalWorkMinutes() != null ? item.getTotalWorkMinutes() : 0;
            item.setTotalPayrollMinutes(base + payableMinuteCorrectionFor(item.getId()));
        }

        // ── 2. mealAllowanceUnitAmount → recalc totalMealAllowanceAmount ─────
        // null = reset to system value; value == system = overridden false; value != system = overridden true
        // The meal price and the transport total are edited ON THEIR LINES now.
        // These two branches wrote the mirror columns and left the recalculation to
        // copy them across; with the line as the source that was one write too many
        // and one more place for the two to disagree. See applyAdjustmentPatch.
        // ── 3. totalTransportAllowanceAmount → sync TRANSPORT adj ─────────────
        // The bonus is edited ON ITS LINE now, both parts of it: baseAmount and
        // correctionAmount in the adjustments array. These branches wrote the item
        // columns and left the recalculation to copy them across.
        // ── 5. Individual adjustment patches ─────────────────────────────────
        if (req.getAdjustments() != null && !req.getAdjustments().isEmpty()) {
            PayrollSchemeScope patchScope = scopeFor(item);
            for (AdjustmentPatchDto adjPatch : req.getAdjustments()) {
                PayrollAdjustment adj = payrollAdjustmentRepository.findByIdWithCategory(adjPatch.getId())
                        .orElseThrow(() -> new IllegalArgumentException("PayrollAdjustment not found: " + adjPatch.getId()));

                if (!adj.getPayrollRunItem().getId().equals(id)) {
                    throw new IllegalArgumentException(
                            "PayrollAdjustment " + adjPatch.getId() + " does not belong to PayrollRunItem " + id);
                }

                // WHAT MAY BE EDITED IS ENFORCED HERE, NOT IN THE UI.
                //
                // Until now allow_override was decoration: the flag said FALSE on
                // meal and transport while both were edited every day, because the
                // patch went through the item columns where nothing read it. A rule
                // only the client honours is a rule anybody with the API can ignore.
                EffectiveComponentConfig config = patchScope.componentConfig(
                        adj.getPayrollAdjustmentCategory().getId());
                applyAdjustmentPatch(adj, adjPatch, config);

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

            // Every applied earning except FIXED_SALARY itself, which is the figure
            // being solved for. Meal is no longer subtracted separately: it is a
            // GROSS_PLUS line like any other and is already in this sum. Subtracting
            // it again — as the old ADDITIONS-based version had to, because meal
            // sits in section MEAL — would now take it off twice.
            BigDecimal otherEarnings = payrollAdjustmentRepository
                    .findByPayrollRunItemIdWithCategory(id)
                    .stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                            && IMPACT_GROSS_PLUS.equals(a.getPayrollAdjustmentCategory().getImpactCode())
                            && !CAT_CODE_FIXED_SALARY.equals(a.getPayrollAdjustmentCategory().getCode()))
                    .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal fixedValue = netEarnings.subtract(otherEarnings)
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
                new java.util.ArrayList<>(
                        payrollRunItemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(item.getId()));

        // A category the monthly report HAS but the item does not gets a row
        // created for it now.
        //
        // Without this, those minutes are silently dropped from payroll: the loop
        // below walks the item's existing rows and matches the monthly report to
        // them, so anything with no row simply never appears. It happens whenever
        // the set of categories changes after a run was initialised — a new
        // category, or an employee moved onto a scheme whose work lands on a
        // category their payroll item was never given a row for.
        //
        // Created regardless of what the scheme allows: this row exists because
        // the work is REAL and already recorded. Refusing it here would lose
        // money to make a display rule tidy.
        java.util.Set<Long> existingCategoryIds = itemCategories.stream()
                .map(c -> c.getWorkCodeCategory().getId())
                .collect(java.util.stream.Collectors.toSet());

        for (MonthlyReportCategory mrc : monthlyCategories) {
            Long wccId = mrc.getWorkCodeCategory().getId();
            if (existingCategoryIds.contains(wccId)) {
                continue;
            }
            PayrollRunItemCategory created =
                    payrollRunItemCategoryRepository.save(newItemCategory(item, mrc.getWorkCodeCategory()));
            itemCategories.add(created);
            existingCategoryIds.add(wccId);
            log.info("PayrollRunItem {}: added missing category row for {} — the monthly report has activity there",
                    item.getId(), mrc.getWorkCodeCategory().getCategoryNo());
        }

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
     * Apply one patch entry, refusing anything the scheme does not permit.
     *
     * <p>Three different edits, kept apart because they mean different things:
     *
     * <ul>
     *   <li>an INPUT edit — the unit price, the quantity, or a correction. The
     *       formula still runs, and the line is not overridden.</li>
     *   <li>a TOTAL override — the amount is typed in and the formula bypassed.
     *       Needs {@code allowTotalOverride} AND a reason.</li>
     *   <li>clearing an override — back to the system figure.</li>
     * </ul>
     *
     * <p>A line the scheme forces to zero refuses all three: a commercial bonus is
     * shown at 0,00 and there is no route, through any client, to put a number in it.
     */
    /**
     * Record that a PERSON did this, on the operations where one did.
     *
     * <p>This replaces trg_payroll_run_items_track_activity, which fired on every
     * update of the row. Recalculation is lazy — opening a payroll recomputes a
     * stale item inside the reader's own request, with their user id in the
     * session — so the trigger reported "user X edited this" for every payroll
     * that user merely opened after a rule change. Only the caller knows the
     * difference, so only the caller says.
     *
     * <p>Silent when nobody is logged in: a background sweep is not activity.
     */
    /**
     * Say, for the rest of this transaction, that what follows is somebody's
     * decision — and record it as this item's last activity.
     *
     * <p>TWO THINGS, ONE PLACE, because they answer the same question and were
     * always going to be called together. It is called from exactly three
     * methods: patch, lock and unlock. Reading a payroll — even when that read
     * recalculates — calls neither.
     *
     * <p>THE SESSION FLAG IS WHAT THE AUDIT TRIGGER WATCHES.
     * trg_audit_logs_payroll_adjustments used to fire on every write, so every
     * recalculation left a full-row diff per line: 20 954 of 33 472 entries in
     * the development database touched nothing but system_*, calculated_at and
     * calculation_inputs, against roughly thirty real decisions. And since a read
     * of a stale item is a write, the count grew whenever anybody opened a
     * payroll. Thirty decisions in thirty thousand entries is not a trail.
     *
     * <p>IT CANNOT BE A WHEN CLAUSE OVER COLUMNS, for the reason 2026-09-03-01
     * gives about activity: a patch and the recalculation it triggers land in the
     * SAME update on the same row, so no column test separates them. What DOES
     * separate them is which method was called, and only the caller knows that.
     * So the caller says so.
     *
     * <p>SET UNCONDITIONALLY, before the user check. A patch made with no
     * authenticated user — a test, a script — is still somebody's decision, and
     * an audit trail that quietly switches off when nobody is logged in is worse
     * than none.
     *
     * <p>{@code true} as the third argument makes it local to the transaction, so
     * it cannot leak into the next thing this connection does from the pool.
     */
    private void markHumanDecision(Long payrollRunItemId) {
        entityManager.createNativeQuery("SELECT set_config('app.records_decision', 'true', true)")
                .getSingleResult();

        Long userId = currentUserService.getCurrentUserId();
        if (userId == null || payrollRunItemId == null) {
            return;
        }
        payrollRunItemUpdateService.upsertActivity(payrollRunItemId, userId);
    }

    /**
     * Refuse any edit to a category the employee's scheme excludes — from EITHER
     * patch route, in one place.
     *
     * <p>THE REASON THIS IS ONE METHOD. The check first went into
     * applyAdjustmentPatch, which handles the {@code adjustments} array. The
     * parameters panel does not use that route: it sends baseBonusAmount and
     * bonusCorrectionAmount as ITEM fields, which land in their own branches
     * below. So a bonus was still accepted for an employee whose scheme forbids
     * it — the same defect, reported a second time, because the fix covered one of
     * two doors.
     *
     * <p>Everything that can put money on one of these three lines now passes
     * through here first. A new field on the request cannot quietly reopen it
     * without being added to this list.
     */
    /**
     * Refuse money edits from a caller who may not see money.
     *
     * <p>Refused LOUDLY rather than ignored: a supervisor who types an hourly
     * rate and gets a silent success believes it was saved. The same rule the
     * employee number follows.
     *
     * <p>The list is the item-level money fields the patch accepts. It is short
     * because almost everything else is edited on its own adjustment line, and
     * those lines are governed by the visibility of their category — which is
     * the configurable layer still to come.
     */
    private void refuseHiddenMoneyEdits(PayrollRunItemPatchRequest req) {
        if (!payrollVisibilityPolicy.isRestrictedUser()) {
            return;
        }
        if (req.isHourlyRatePresent()) {
            throw new ConflictException(
                    "Nemate pravo da menjate satnicu na obračunu.");
        }
    }

    private void refuseEditsToExcludedCategories(PayrollRunItem item, PayrollRunItemPatchRequest req) {
        // Nothing left: meal, transport and the bonus are all edited on their
        // lines, and applyAdjustmentPatch refuses a category the scheme excludes
        // for every one of them. Kept as the place that answer belongs, so a new
        // item-level money field cannot quietly reopen the hole this closed.
    }

    private void applyAdjustmentPatch(PayrollAdjustment adj, AdjustmentPatchDto patch,
                                      EffectiveComponentConfig config) {
        String code = adj.getPayrollAdjustmentCategory().getCode();

        if (config == null) {
            throw new IncompletePayrollConfigurationException(
                    "Stavka " + code + " nema pravilo za način obračuna ovog zaposlenog.");
        }
        // A CATEGORY THE SCHEME EXCLUDES IS NOT EDITABLE. This was never checked:
        // config == null and isForcedZero were, config.allowed() was not. A bonus
        // was entered for an employee whose scheme forbids the category, the patch
        // re-applied the line, and 8.000 went into total_net_earnings — until the
        // next recalculation quietly took it out again. Found by verifying a real
        // month, not by a test.
        if (!config.allowed() && touchesAValue(patch)) {
            throw new ConflictException(
                    "Stavka " + code + " ne pripada ovom zaposlenom po njegovom načinu obračuna.");
        }
        if (config.isForcedZero() && touchesAValue(patch)) {
            throw new ConflictException(
                    "Stavka " + code + " se po ovom načinu obračuna ne unosi i uvek je nula.");
        }

        if (Boolean.TRUE.equals(patch.getClearOverride())) {
            adj.setAmount(adj.getSystemAmount());
            adj.setIsOverridden(false);
            adj.setOverrideReason(null);
        }

        if (patch.getQuantity() != null) {
            requireEditableInput(config, "QUANTITY", code);
            adj.setQuantity(patch.getQuantity().setScale(4, RoundingMode.HALF_UP));
            adj.setHasManualInput(true);
        }
        if (patch.getUnitAmount() != null) {
            requireEditableInput(config, "UNIT_AMOUNT", code);
            adj.setUnitAmount(patch.getUnitAmount().setScale(4, RoundingMode.HALF_UP));
            adj.setHasManualInput(true);
        }
        if (patch.getBaseAmount() != null) {
            // Only where a correction is what the scheme lets a person edit — that
            // is the shape this exists for, and permitting it everywhere would be a
            // second route to a total on lines that allow no such thing.
            requireEditableInput(config, "CORRECTION", code);
            adj.setAmount(patch.getBaseAmount()
                    .add(orZero(adj.getCorrectionAmount()))
                    .setScale(2, RoundingMode.HALF_UP));
            adj.setHasManualInput(true);
        }
        if (patch.getCorrectionAmount() != null) {
            requireEditableInput(config, "CORRECTION", code);
            // The base is amount minus the OLD correction; keep it and put the new
            // correction on top, or changing the tier would silently move the base.
            BigDecimal keptBase = orZero(adj.getAmount()).subtract(orZero(adj.getCorrectionAmount()));
            adj.setCorrectionAmount(patch.getCorrectionAmount().setScale(2, RoundingMode.HALF_UP));
            adj.setAmount(keptBase.add(adj.getCorrectionAmount()).setScale(2, RoundingMode.HALF_UP));
            adj.setHasManualInput(true);
        }

        if (patch.getAmount() != null) {
            BigDecimal amount = patch.getAmount().setScale(2, RoundingMode.HALF_UP);

            // THE ORDER HERE MATTERS, and getting it wrong made every manual line
            // uneditable. A MANUAL category has no calculator, so its system_amount
            // stays 0 — and comparing against 0 said that typing 5.000 into
            // PAID_PART_2 "bypassed the formula" and demanded allow_total_override.
            // There is no formula to bypass. When the scheme names AMOUNT as the
            // editable input, the amount IS the input.
            boolean amountIsTheInput = "AMOUNT".equals(config.editableInput());
            boolean differsFromSystem = adj.getSystemAmount() != null
                    && amount.compareTo(adj.getSystemAmount()) != 0;

            if (amountIsTheInput) {
                // Nothing to check beyond the policy already allowing it.
            } else if (differsFromSystem) {
                // A figure the calculation did not produce. Whether that is allowed
                // is the scheme's to say, and D7 requires a reason for it either way
                // — the audit trail records who and when, but only this says what
                // the decision was.
                if (!config.allowTotalOverride()) {
                    throw new ConflictException(
                            "Ukupan iznos stavke " + code + " se ne može uneti ručno.");
                }
                if (patch.getOverrideReason() == null || patch.getOverrideReason().isBlank()) {
                    throw new ConflictException(
                            "Razlog je obavezan kada se ručno unosi ukupan iznos stavke " + code + ".");
                }
                adj.setIsOverridden(true);
                adj.setOverrideReason(patch.getOverrideReason());
            } else if (!config.allowTotalOverride()) {
                // Not an amount-editable line, and the figure sent is the system's
                // own — a no-op. Accepted where a total override is permitted, so
                // that re-saving a form does not fail; refused otherwise.
                requireEditableInput(config, "AMOUNT", code);
            }

            adj.setAmount(amount);
            // Somebody has now answered for this line. Zero counts as an answer;
            // the flag is what separates it from silence.
            adj.setHasManualInput(true);
        }

        // AN INPUT EDIT MUST LEAVE THE LINE ADDING UP. Setting a unit price and
        // leaving `amount` at the old figure is what the item-column branch used to
        // recompute on the caller's behalf — with that branch gone, the line has to
        // apply its own formula, which is the one the recalculation uses:
        //
        //     amount = (quantity ?? system_quantity) × (unit_amount ?? system_unit_amount)
        //            + correction_amount
        //
        // Not when the total was typed in: then there is no formula to apply.
        if (patch.getAmount() == null
                && patch.getBaseAmount() == null && patch.getCorrectionAmount() == null
                && (patch.getQuantity() != null || patch.getUnitAmount() != null)
                && !Boolean.TRUE.equals(adj.getIsOverridden())) {
            BigDecimal qty = adj.getQuantity() != null ? adj.getQuantity() : adj.getSystemQuantity();
            BigDecimal unit = adj.getUnitAmount() != null ? adj.getUnitAmount() : adj.getSystemUnitAmount();
            if (qty != null && unit != null) {
                adj.setAmount(qty.multiply(unit)
                        .add(adj.getCorrectionAmount() != null ? adj.getCorrectionAmount() : BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP));
            }
        }

        if (patch.getIsApplied() != null) {
            adj.setIsApplied(patch.getIsApplied());
        }
        if (patch.getNote() != null) {
            adj.setNote(patch.getNote());
        }
    }

    /**
     * Does this patch put a figure on the line?
     *
     * <p>EVERY money-bearing field has to be listed. baseAmount was added and this
     * was not, so a bonus base sailed past the excluded-category check while a
     * total and a correction were both refused — the same defect the user reported
     * twice, reopened by a new field. The tests caught it; the review did not.
     */
    private static boolean touchesAValue(AdjustmentPatchDto patch) {
        return patch.getAmount() != null || patch.getQuantity() != null
                || patch.getUnitAmount() != null || patch.getCorrectionAmount() != null
                || patch.getBaseAmount() != null;
    }

    private void requireEditableInput(EffectiveComponentConfig config, String wanted, String code) {
        if (!wanted.equals(config.editableInput())) {
            throw new ConflictException(
                    "Stavka " + code + " ne dozvoljava izmenu ovog polja"
                            + ("NONE".equals(config.editableInput())
                                    ? "." : " — dozvoljeno je: " + config.editableInput() + "."));
        }
    }

    /**
     * The inputs one calculator run is allowed to see, resolved for the period.
     *
     * <p>Everything date-effective is read at {@code pricingDate}, so a calculator
     * cannot reach for {@code now()} even by accident. It is also the shape a
     * payroll run needs: the two lookups here are the ones phase 4 hoists out of
     * the loop and does once for the whole batch.
     */
    private ComponentContext componentContextFor(PayrollRunItem item, MonthlyReport mr,
                                                 LocalDate pricingDate) {
        Long employeeId = item.getEmployee() == null ? null : item.getEmployee().getId();

        Map<String, BigDecimal> employeeValues = employeeId == null
                ? Map.of()
                : employeePayrollValueService.numericValuesOn(List.of(employeeId), pricingDate)
                        .getOrDefault(employeeId, Map.of());

        // The BOOLEAN half of the same history: which modes this employee is on.
        java.util.Set<String> employeeFlags = employeeId == null
                ? java.util.Set.of()
                : employeePayrollValueService.trueFlagsOn(List.of(employeeId), pricingDate)
                        .getOrDefault(employeeId, java.util.Set.of());

        // EVERYTHING IS READ AT THE MONTH'S FIRST DAY — the company prices and the
        // employee's own values alike. A payroll month is priced by what was true
        // when it started, so a price raised mid-month applies from the NEXT month.
        // Confirmed by the client on 2026-08-04, for both prices, after a day spent
        // trying the other rule.
        //
        // Never now(): reading these at today's date is what made recalculating
        // March in July charge July's prices.
        Map<String, BigDecimal> settings = new java.util.HashMap<>();
        settings.put(MealAllowanceCalculator.SETTING_MEAL_PER_DAY,
                appSettingService.getMealAllowancePerDayOn(pricingDate));
        settings.put(TransportAllowanceCalculator.SETTING_TRANSPORT_PER_DAY,
                appSettingService.getTransportAllowancePerDayOn(pricingDate));

        return new ComponentContext(item, mr, mr.getStartDate(), mr.getEndDate(),
                employeeValues, settings, employeeFlags);
    }

    /**
     * Write a calculated line onto its adjustment row.
     *
     * <p>Also records what the calculator was given. A line that comes out at zero
     * is common and legitimate — no rate configured, no qualifying shift, excluded
     * by the scheme — and without the reason none of those can be told apart from
     * a fault when somebody asks why a payslip changed.
     */




    /** Put a price a person set on the line, where the recalculation reads it. */
    private void recordHumanUnitAmount(Long itemId, String categoryCode, BigDecimal unitAmount) {
        payrollAdjustmentRepository.findByItemIdAndCategoryCode(itemId, categoryCode)
                .ifPresent(adj -> {
                    adj.setUnitAmount(unitAmount);
                    adj.setHasManualInput(true);
                    payrollAdjustmentRepository.save(adj);
                });
    }

    /**
     * Mark a line as carrying a figure a person entered.
     *
     * <p>has_manual_input, not is_overridden: this path has no reason to record and
     * chk_pa_override_reason refuses a flagged row without one. What is known is
     * that a person entered it, and that is what gets written.
     */
    private void recordHumanAmount(Long itemId, String categoryCode) {
        payrollAdjustmentRepository.findByItemIdAndCategoryCode(itemId, categoryCode)
                .ifPresent(adj -> {
                    adj.setHasManualInput(true);
                    payrollAdjustmentRepository.save(adj);
                });
    }

    /**
     * Whether a figure on the line is a person's rather than the calculation's.
     *
     * <p>STEP 2 READS THIS INSTEAD OF payroll_run_items.*_overridden. The five
     * flags on the item said the same thing about three lines, in columns nothing
     * else could reach; the line has always been where the figure itself lives.
     *
     * <p>The test is a comparison, not a flag, because the line has one
     * has_manual_input for a row that can carry two independently editable parts —
     * the bonus base and its tier. Comparing each part against what the rules
     * produced answers per part.
     *
     * <p>The one case it gets "wrong" is somebody typing exactly the figure the
     * system produced. The recalculation then overwrites it with the same number,
     * which changes nothing and is why this is safe here — unlike inferring
     * is_overridden the same way, which mislabelled ordinary recalculations as
     * human decisions and is what 2026-08-12-01 had to undo.
     */
    private static boolean differsFromSystem(BigDecimal effective, BigDecimal system) {
        if (effective == null) {
            return false;
        }
        return effective.compareTo(system != null ? system : BigDecimal.ZERO) != 0;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** The three calculated lines of an item, by category code. */
    private Map<String, PayrollAdjustment> calculatedLines(Long itemId) {
        return payrollAdjustmentRepository.findByPayrollRunItemIdWithCategory(itemId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        a -> a.getPayrollAdjustmentCategory().getCode(), a -> a, (a, b) -> a));
    }

    /**
     * Drop an override flag that carries no reason, when the calculation is about
     * to write over the amount anyway.
     *
     * <p>{@code chk_pa_override_reason} arrived NOT VALID (2026-08-25-01) so that
     * overrides recorded before the rule existed were not given invented
     * explanations. NOT VALID exempts existing rows from the initial check — it
     * does NOT exempt them from being checked when something UPDATES them. So the
     * 24 rows in that state sat quietly until a recalculation touched one, and
     * then the whole recalculation failed: 12 payroll items could not be
     * recalculated at all.
     *
     * <p>Clearing the flag here states what becomes true in the same write: the
     * row now holds the figure the calculation produced, so it is not somebody's
     * typed-in total any more. The alternative — writing "migrated" into a field
     * meant for a person's explanation — would put words in their mouth, which is
     * exactly what the NOT VALID was avoiding.
     *
     * <p>An override WITH a reason is left untouched. This only reaches rows the
     * old rule allowed to exist.
     */
    private static void clearUnreasonedOverride(PayrollAdjustment adjustment) {
        if (Boolean.TRUE.equals(adjustment.getIsOverridden())
                && (adjustment.getOverrideReason() == null
                    || adjustment.getOverrideReason().isBlank())) {
            adjustment.setIsOverridden(false);
        }
    }

    private void syncAdjustment(Long itemId, String categoryCode, BigDecimal amount,
                                ComponentResult result, boolean applied) {
        syncAdjustment(itemId, categoryCode, amount, result, applied, true);
    }

    /**
     * @param writeAmount false when a person's figure is being kept.
     *
     * <p>THE SYSTEM FIGURES ARE WRITTEN EITHER WAY. Skipping the whole sync when a
     * human value wins left the line's system_amount, system_quantity,
     * system_unit_amount, calculation_inputs and calculated_at frozen at whatever
     * the last automatic run produced — so the line could not say what the
     * calculation WOULD have paid, which is the entire point of keeping the system
     * values beside the effective ones. It also made those lines look never
     * calculated: two of them still read that way in the step-3 report after a
     * sweep that had in fact visited them.
     */
    private void syncAdjustment(Long itemId, String categoryCode, BigDecimal amount,
                                ComponentResult result, boolean applied, boolean writeAmount) {
        payrollAdjustmentRepository.findByItemIdAndCategoryCode(itemId, categoryCode)
                .ifPresent(adjustment -> {
                    if (writeAmount) {
                        clearUnreasonedOverride(adjustment);
                        adjustment.setAmount(amount);
                    }
                    adjustment.setSystemAmount(result.systemAmount());
                    adjustment.setSystemQuantity(result.systemQuantity());
                    adjustment.setSystemUnitAmount(result.systemUnitAmount());
                    adjustment.setIsApplied(applied);
                    adjustment.setCalculationInputs(result.inputs());
                    adjustment.setCalculatedAt(OffsetDateTime.now());
                    adjustment.setUpdatedAt(OffsetDateTime.now());
                    payrollAdjustmentRepository.save(adjustment);
                });
    }

    private static int intValue(BigDecimal value) {
        return value == null ? 0 : value.intValue();
    }

    /**
     * Finds an adjustment by category code and updates its amount and isApplied flag.
     * Used for categories where calculation_key is NULL (e.g. FIXED_SALARY).
     */
    private void updateAdjustmentByCategoryCode(Long itemId, String categoryCode, BigDecimal amount, boolean isApplied) {
        updateAdjustmentByCategoryCode(itemId, categoryCode, amount, isApplied, false);
    }

    /**
     * @param humanOverride whether this write is a person replacing the final
     *        amount, as opposed to the system writing what it computed.
     *
     * <p>The caller has to say which, because the value alone cannot. This method
     * used to infer it — {@code isOverridden = amount != systemAmount} — and got it
     * wrong in the common case: a recalculation writes the system's own figure and
     * was then marking the line as overridden by a human. It also could not tell a
     * repriced meal, which is an edit to an INPUT with the formula still running,
     * from a typed-in total, which bypasses the formula. D7 needs those apart.
     */
    private void updateAdjustmentByCategoryCode(Long itemId, String categoryCode, BigDecimal amount,
                                                boolean isApplied, boolean humanOverride) {
        payrollAdjustmentRepository.findByItemIdAndCategoryCode(itemId, categoryCode)
                .ifPresent(adj -> {
                    clearUnreasonedOverride(adj);
                    adj.setAmount(amount);
                    adj.setIsApplied(isApplied);
                    if (humanOverride) {
                        adj.setIsOverridden(
                                adj.getSystemAmount() != null
                                        && amount.compareTo(adj.getSystemAmount()) != 0);
                    }
                    adj.setUpdatedAt(OffsetDateTime.now());
                    payrollAdjustmentRepository.save(adj);
                });
    }

    /**
     * Take every adjustment the scheme excludes out of the arithmetic.
     *
     * <p>Not merely hidden: zeroed and un-applied, so it contributes nothing to
     * any sum. Every total in this class filters on {@code isApplied}, so this is
     * what makes "does not affect this employee" literally true rather than
     * cosmetically true.
     *
     * <p>Rows are neutralised rather than deleted. A run initialised before a
     * scheme change already has them, deleting is irreversible, and an
     * administrator who moves the employee back gets the line back with no data
     * lost.
     */
    private void neutraliseExcludedAdjustments(PayrollRunItem item, PayrollSchemeScope scope) {
        if (scope == null) {
            return;
        }
        List<PayrollAdjustment> adjustments =
                payrollAdjustmentRepository.findByPayrollRunItemIdWithCategory(item.getId());

        List<PayrollAdjustment> changed = new java.util.ArrayList<>();
        for (PayrollAdjustment adjustment : adjustments) {
            Long categoryId = adjustment.getPayrollAdjustmentCategory().getId();
            if (scope.allowsAdjustmentCategory(categoryId)) {
                continue;
            }
            boolean wasCounted = Boolean.TRUE.equals(adjustment.getIsApplied())
                    || (adjustment.getAmount() != null && adjustment.getAmount().compareTo(BigDecimal.ZERO) != 0);
            if (!wasCounted) {
                continue;
            }
            adjustment.setIsApplied(false);
            adjustment.setAmount(BigDecimal.ZERO);
            adjustment.setSystemAmount(BigDecimal.ZERO);
            adjustment.setUpdatedAt(OffsetDateTime.now());
            changed.add(adjustment);
        }

        if (!changed.isEmpty()) {
            payrollAdjustmentRepository.saveAll(changed);
            log.info("PayrollRunItem {}: {} adjustment line(s) excluded by the compensation scheme were zeroed",
                    item.getId(), changed.size());
        }
    }

    /**
     * An empty payroll category row.
     *
     * <p>Mirrors {@code PayrollRunInitializationTxService.buildItemCategory} —
     * the same shape, created at a different moment. Every value is filled in by
     * the population loop immediately afterwards; this only has to be valid
     * enough to insert.
     */
    private PayrollRunItemCategory newItemCategory(PayrollRunItem item,
                                                   com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory wcc) {
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
        cat.setHourlyRate(Boolean.TRUE.equals(wcc.getFixedHourlyRate()) && wcc.getHourlyRate() != null
                ? wcc.getHourlyRate()
                : (item.getHourlyRate() != null ? item.getHourlyRate() : BigDecimal.ZERO));
        cat.setAmount(BigDecimal.ZERO);
        cat.setCategoryAffectsNormSnapshot("WORK".equals(wcc.getType()));
        cat.setCategoryAffectsBonusSnapshot("WORK".equals(wcc.getType()));
        cat.setCreatedAt(OffsetDateTime.now());
        return cat;
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
    private PayrollRunItem recalculateFromMonthlyReport(PayrollRunItem item, MonthlyReport mr,
                                                        PayrollSchemeScope scope) {

        // `scope` is what this employee's compensation scheme allows across this
        // month, resolved by the caller so that a payroll run resolves it once for
        // the whole batch. null means unrestricted.

        // EVERY DATE-EFFECTIVE VALUE IN THIS METHOD IS READ AT THIS DATE, never at
        // now(): the rate, the meal price, the transport price. The monthly
        // report's start date IS the payroll month and is NOT NULL, so the period
        // is always known and no fallback is needed. Reading these with now() is
        // what made recalculating March in July charge July's prices.
        LocalDate pricingDate = mr.getStartDate();

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

        // ── Hourly rate: the rate in force FOR THIS PERIOD ────────────────────
        //
        // The value history first, because it is the only source that knows what
        // the rate was in March as opposed to what it is today. employees.hourly_rate
        // is the fallback for anyone the backfill could not reconstruct — it is
        // still today's truth, and reading it is exactly what happened before, so
        // the fallback can only reproduce old behaviour, never make it worse.
        //
        // NEITHER source having an answer leaves hourly_rate_system untouched,
        // which is also what happened before: 923 of 949 items calculate at rate 0
        // and must keep doing so. Overwriting with zero here would be a change
        // dressed up as a refactor.
        BigDecimal employeeRate = hourlyRateFor(item, pricingDate);
        if (employeeRate != null) {
            item.setHourlyRateSystem(employeeRate);
            if (!Boolean.TRUE.equals(item.getHourlyRateOverridden())) {
                item.setHourlyRate(employeeRate);
            }
        }

        // ── Payroll minutes ───────────────────────────────────────────────────
        // The corrections are rows. Summing them rather than reading the column
        // means two corrections with different causes add up correctly, and one
        // can be withdrawn without anybody recomputing the other by hand.
        int minuteCorrection = payableMinuteCorrectionFor(item.getId());
        item.setManualAdjustedMinutes(minuteCorrection);   // derived; not persisted
        item.setTotalPayrollMinutes(safe(mr.getTotalWorkMinutes()) + minuteCorrection);

        // ── Meal allowance count + recalc total ───────────────────────────────
        //
        // Zeroed here, not merely hidden. totalNetEarnings adds
        // item.totalMealAllowanceAmount DIRECTLY, not through the adjustment
        // line, so suppressing only the adjustment row would remove the line
        // from the payslip while still paying the money.
        // The maths for these two lines now lives in a calculator each. This method
        // still writes both the item columns AND the adjustment rows — the double
        // bookkeeping is phase 4's to remove, not phase 3's.
        ComponentContext calcContext = componentContextFor(item, mr, pricingDate);

        ComponentResult meal = calculatorRegistry
                .require(CalculationKeys.MEAL_BY_ELIGIBLE_SHIFTS).calculate(calcContext);

        boolean mealAllowed = allowsAdjustmentCode(scope, "MEAL_ALLOWANCE");
        int mealCount = !mealAllowed ? 0 : intValue(meal.systemQuantity());

        BigDecimal mealSystemRate = meal.systemUnitAmount() != null
                ? meal.systemUnitAmount() : BigDecimal.ZERO;

        // STEP 2: the price a person set is read from the LINE, not from
        // meal_allowance_unit_amount_overridden. The column is still written, from
        // the line, until phase 7 drops it.
        Map<String, PayrollAdjustment> lines = calculatedLines(item.getId());
        PayrollAdjustment mealLine = lines.get("MEAL_ALLOWANCE");
        boolean mealPriceIsHuman = mealLine != null && mealAllowed
                && differsFromSystem(mealLine.getUnitAmount(), mealLine.getSystemUnitAmount());

        BigDecimal mealUnitAmt = mealPriceIsHuman ? mealLine.getUnitAmount() : mealSystemRate;
        BigDecimal totalMeal = mealUnitAmt.multiply(BigDecimal.valueOf(mealCount)).setScale(2, RoundingMode.HALF_UP);
        syncAdjustment(item.getId(), "MEAL_ALLOWANCE", totalMeal, meal, mealAllowed);

        // ── Transport allowance ───────────────────────────────────────────────
        // Same reasoning as the meal allowance above: the item column feeds the
        // total directly, so it has to be zeroed and not just left unlinked.
        //
        // THE UNIT PRICE IS NOW THE EMPLOYEE'S, NOT THE GLOBAL SETTING. Transport
        // is paid to some people and not others, at rates that differ per person,
        // so a single app_settings figure could never express it — which is why
        // transport_allowance_days was never computed and the whole line has been
        // structurally zero. An employee with no TRANSPORT_FIXED_MONTHLY in force gets 0,
        // and that is a correct answer rather than a fault.
        boolean transportAllowed = allowsAdjustmentCode(scope, CAT_CODE_TRANSPORT);
        ComponentResult transport = transportAllowed
                ? calculatorRegistry.require(CalculationKeys.TRANSPORT_BY_QUALIFYING_SHIFTS)
                        .calculate(calcContext)
                : ComponentResult.zero("EXCLUDED_BY_SCHEME");

        // The count and the unit price live on the TRANSPORT_ALLOWANCE line as
        // system_quantity and system_unit_amount — the item columns that used to
        // mirror them were read by nothing and are gone (2026-08-31-01).
        BigDecimal totalTransport = transport.systemAmount();

        // STEP 2: was total_transport_allowance_amount_overridden. is_overridden is
        // the modern record and carries a reason; has_manual_input covers the rows
        // that predate that rule and were settled without one.
        // transportAllowed for the same reason as the bonus above: an excluded line
        // is about to be zeroed, and its leftover amount is not somebody's decision.
        PayrollAdjustment transportLine = lines.get(CAT_CODE_TRANSPORT);
        boolean transportIsHuman = transportLine != null && transportAllowed
                && (Boolean.TRUE.equals(transportLine.getIsOverridden())
                    || (Boolean.TRUE.equals(transportLine.getHasManualInput())
                        && differsFromSystem(transportLine.getAmount(), transportLine.getSystemAmount())));

        syncAdjustment(item.getId(), CAT_CODE_TRANSPORT, totalTransport, transport,
                transportAllowed, !transportIsHuman);

        // ── Monthly bonus ─────────────────────────────────────────────────────
        // The base bonus and the hours tier are a rule, not a number somebody types
        // in every month. An override still wins: totalBonusAmountOverridden is
        // checked exactly as meal and transport are.
        boolean bonusAllowed = allowsAdjustmentCode(scope, CAT_CODE_BONUS)
                && (scope == null || scope.allowsPerformanceBonus());
        ComponentResult bonus = bonusAllowed
                ? calculatorRegistry.require(CalculationKeys.MONTHLY_BONUS_FROM_RULES)
                        .calculate(calcContext)
                : ComponentResult.zero(scope != null && !scope.allowsPerformanceBonus()
                        ? "SCHEME_PAYS_NO_BONUS" : "EXCLUDED_BY_SCHEME");

        // THE BONUS HAS TWO NAMED PARTS AND THE LINE CARRIES BOTH.
        //
        //   base       — the employee's own amount from bonus_categories, resolved
        //                for the period through employees_bonus_history, granted
        //                whole or not at all once the month's minimum hours are met
        //   correction — the hours tier from bonus_eligibility_rules
        //   total      — the two added together
        //
        // The line keeps `amount` as the EFFECTIVE TOTAL — what is paid, and what
        // the earnings sum reads — and `correction_amount` as the tier, so the
        // effective base is the difference. Narrowing amount to the base alone
        // would have meant changing that sum to amount + correction_amount in the
        // same breath, and getting the order of those two wrong is a bonus paid
        // twice.
        //
        // Nine item columns used to mirror this and are gone. They were written
        // from these same figures and read by nothing that had not already been
        // moved to the line.
        BigDecimal baseSystem = bonus.numericInput(MonthlyBonusCalculator.INPUT_BASE_BONUS)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal correctionSystem = bonus.numericInput(MonthlyBonusCalculator.INPUT_TIER_BONUS)
                .setScale(2, RoundingMode.HALF_UP);

        PayrollAdjustment bonusLine = lines.get(CAT_CODE_BONUS);

        BigDecimal effectiveCorrection = correctionSystem;
        BigDecimal effectiveBase = baseSystem;
        boolean totalIsTyped = false;

        // A category the scheme excludes takes NOTHING from the line. Its row is
        // zeroed and un-applied later by neutraliseExcludedAdjustments, so reading
        // a leftover figure off it here and calling that a human's decision showed
        // a 4.000 bonus for an employee whose scheme pays none.
        if (bonusLine != null && bonusAllowed) {
            totalIsTyped = Boolean.TRUE.equals(bonusLine.getIsOverridden());

            if (differsFromSystem(bonusLine.getCorrectionAmount(), bonusLine.getSystemCorrectionAmount())) {
                effectiveCorrection = bonusLine.getCorrectionAmount();
            }
            BigDecimal lineBase = orZero(bonusLine.getAmount())
                    .subtract(orZero(bonusLine.getCorrectionAmount()));
            BigDecimal lineSystemBase = orZero(bonusLine.getSystemAmount())
                    .subtract(orZero(bonusLine.getSystemCorrectionAmount()));
            if (differsFromSystem(lineBase, lineSystemBase)) {
                effectiveBase = lineBase;
            }
        }

        if (!totalIsTyped) {
            BigDecimal totalBonus = effectiveBase.add(effectiveCorrection)
                    .setScale(2, RoundingMode.HALF_UP);
            syncAdjustment(item.getId(), CAT_CODE_BONUS, totalBonus, bonus, bonusAllowed);
            // The tier, recorded on the line beside the total it is part of.
            if (bonusLine != null) {
                bonusLine.setSystemCorrectionAmount(correctionSystem);
                bonusLine.setCorrectionAmount(effectiveCorrection);
                payrollAdjustmentRepository.save(bonusLine);
            }
        } else {
            // The rules' own figures still belong on the line, even though the
            // total is somebody's: that is how the panel shows what would
            // otherwise have been paid.
            syncAdjustment(item.getId(), CAT_CODE_BONUS, bonus.systemAmount(), bonus,
                    bonusAllowed, false);

            // A TYPED TOTAL HAS NO PARTS — that is what overriding a total means.
            //
            // Left alone, the parts kept whatever split preceded the override: one
            // real item read "base 0 + additional 2.000" beside a line holding
            // 2.000 as base. Subtracting the rules' tier from the typed figure is
            // no better — a total of 2.000 against a 2.500 tier gives a base of
            // MINUS 500, which is arithmetic nobody typed and nobody means.
            //
            // So the whole figure is the base and the additional is nothing: the
            // correction goes to zero and the total stays where it was put. The
            // panel then reads "2.000 + 0 = 2.000", which is what somebody decided;
            // the rules' figures are still there as system_amount and
            // system_correction_amount.
            if (bonusLine != null) {
                bonusLine.setSystemCorrectionAmount(correctionSystem);
                bonusLine.setCorrectionAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                payrollAdjustmentRepository.save(bonusLine);
            }
        }

        // ── previousNetPayableAmount — from previous month's item for this employee ──
        if (item.getPeriod() != null && item.getEmployee() != null) {
            LocalDate prevPeriod = item.getPeriod().minusMonths(1).withDayOfMonth(1);
            payrollRunItemRepository.findByEmployee_IdAndPeriod(item.getEmployee().getId(), prevPeriod)
                    .stream().findFirst()
                    .ifPresent(prev -> item.setPreviousNetPayableAmount(prev.getNetPayableAmount()));
        }

        // ── Populate item categories from monthly report categories ───────────
        neutraliseExcludedAdjustments(item, scope);
        populateItemCategoriesFromMonthlyReport(item, mr, scope);

        // ── Recalculate summary totals from adjustment lines ──────────────────
        recalculateSummaryTotals(item);

        // ── Version stamp ─────────────────────────────────────────────────────
        item.setBasedOnVersion(mr.getVersion());
        item.setNeedsRecalculation(false);
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

                    // Update PHONE_PREVIOUS_MONTH from THIS month's phone LINE.
                    // It used to come from the column beside it, which meant a
                    // phone entered on the line alone was never charged on.
                    final BigDecimal phoneFinal = payrollAdjustmentRepository
                            .findByItemIdAndCategoryCode(item.getId(), "PHONE_CURRENT_MONTH")
                            .map(PayrollAdjustment::getAmount)
                            .orElse(BigDecimal.ZERO);
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

        // EVERY EARNING COMES FROM ITS ADJUSTMENT ROW, ONCE.
        //
        // Meal and transport used to be added from item columns as well, with their
        // adjustment rows excluded by code so the money was not counted twice. That
        // is the double bookkeeping this phase ends: the row is the source, the
        // columns are a mirror kept for one cycle and dropped in phase 7.
        //
        // Summed by IMPACT, not by section. GROSS_PLUS is exactly
        // {MEAL_ALLOWANCE, TRANSPORT_ALLOWANCE, FIXED_SALARY, MONTHLY_BONUS, OTHER,
        // POSITIVE_NEGATIVE_CORRECTION} — the same money as before, reached without
        // the special cases, and without depending on which section a category was
        // moved into for display.
        BigDecimal earningsSum = adjustments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && IMPACT_GROSS_PLUS.equals(a.getPayrollAdjustmentCategory().getImpactCode()))
                .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalNetEarnings = categoriesSum
                .add(earningsSum)
                .setScale(2, RoundingMode.HALF_UP);
        item.setTotalNetEarnings(totalNetEarnings);

        // total_deductions_amount is gone. It summed every DEDUCTION_MINUS line,
        // which put PAID_PART_2 — money already paid OUT to the employee — and
        // PHONE_CURRENT_MONTH — which is charged next month, not this one — beside
        // the two real deductions. Displayed nowhere, which is why it was never
        // questioned. "Ukupna odbijanja" is a figure the business defines, and then
        // it is one sum over the lines, computed where it is shown.

        // ── previouslyPaidAmount ──────────────────────────────────────────────
        //
        // STILL FILTERED BY SECTION, and deliberately so. Switching this side to
        // impact codes as well would pull in PHONE_CURRENT_MONTH and
        // PAID_PREVIOUS_PERIOD, which reach no total today — the current month's
        // phone is deducted NEXT month as PHONE_PREVIOUS_MONTH, and
        // PAID_PREVIOUS_PERIOD is a display mirror. Making either start reducing
        // somebody's pay is a business decision, not a refactor. See OPEN-12.
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

        writeDerivedSettlementLines(item);
    }

    /**
     * The two lines that SHOW a total rather than contributing to one.
     *
     * <p>Written here, at the end of the summary, because they are projections of
     * numbers this method has just computed — {@code previouslyPaidAmount} and
     * {@code previousNetPayableAmount}. Computing them anywhere else would let the
     * payslip disagree with its own total.
     *
     * <p>Both sit in sections that reach no balance ({@code SETTLEMENTS_SUM} and
     * {@code BALANCE}) and that is essential, not incidental:
     * {@code PAID_PREVIOUS_PERIOD} IS the settlements sum, so counting it among
     * them would deduct everything twice, and {@code PREVIOUS_BALANCE} is already
     * inside {@code netPayableAmount}.
     */
    private void writeDerivedSettlementLines(PayrollRunItem item) {
        MonthlyReport mr = item.getMonthlyReport();
        if (mr == null || mr.getStartDate() == null) {
            log.debug("PayrollRunItem {} has no period — skipping the derived settlement lines",
                    item.getId());
            return;
        }
        ComponentContext ctx = componentContextFor(item, mr, mr.getStartDate());

        ComponentResult paid = calculatorRegistry
                .require(CalculationKeys.PAID_PREVIOUS_PERIOD_SUM).calculate(ctx);
        syncAdjustment(item.getId(), "PAID_PREVIOUS_PERIOD", paid.systemAmount(), paid, true);

        ComponentResult carried = calculatorRegistry
                .require(CalculationKeys.PREVIOUS_BALANCE_CARRIED).calculate(ctx);
        syncAdjustment(item.getId(), "PREVIOUS_BALANCE", carried.systemAmount(), carried, true);
    }

    /** Re-fetches with the monthly report joined and recalculates if version is stale or needs_recalculation is set. */
    private PayrollRunItem refreshIfStale(PayrollRunItem item, ScopeSource scopes) {
        return payrollRunItemRepository.findByIdWithMonthlyReport(item.getId())
                .map(fresh -> {
                    MonthlyReport mr = fresh.getMonthlyReport();
                    if (mr == null) return fresh;

                    // WHICH ROWS EXIST AND WHAT THEY ARE WORTH ARE GUARDED
                    // DIFFERENTLY, and this is why. monthly_reports.version
                    // tracks the CONTENT of the report, so it is the right guard
                    // for the amounts. It says nothing about the ROW SET, which
                    // also depends on the employee's compensation scheme — and
                    // moving an employee to another scheme does not touch the
                    // monthly report at all.
                    //
                    // So the row set is reconciled first, unconditionally. It is
                    // a cheap idempotent INSERT of what is missing and never
                    // touches an amount. Without it an item that was refreshed
                    // before a scheme change looks up to date forever and simply
                    // never grows the row its work now lands on.
                    PayrollSchemeScope scope = scopes.scopeOf(fresh);
                    boolean addedRowWithActivity = reconcileItemCategories(fresh, mr, scope);

                    Integer latest = mr.getVersion();
                    Integer used   = fresh.getBasedOnVersion();
                    boolean versionStale   = latest != null && !latest.equals(used);
                    boolean flaggedForRecalc = Boolean.TRUE.equals(fresh.getNeedsRecalculation());
                    if (!versionStale && !flaggedForRecalc && !addedRowWithActivity) return fresh;
                    return recalculateFromMonthlyReport(fresh, mr, scope);
                })
                .orElse(item);
    }

    /**
     * Give the item a row for every category it can legitimately carry.
     *
     * <p>Two sources, unioned:
     * <ul>
     *   <li>what the employee's scheme says is payable — so a category work is
     *       mapped INTO gets its row even in a month with no activity yet, which
     *       is what makes it a category this worker type "has";</li>
     *   <li>what the monthly report actually has activity in — the money-safety
     *       net, applied whatever the scheme says, because a row missing here
     *       means those minutes never reach payroll.</li>
     * </ul>
     *
     * <p>Only inserts. Never deletes and never touches an amount, so it is safe
     * to run on any read.
     *
     * @return true when a row was added that the monthly report has activity for,
     *         meaning the amounts are now out of date and must be recalculated
     */
    private boolean reconcileItemCategories(PayrollRunItem item, MonthlyReport mr,
                                            PayrollSchemeScope scope) {
        if (STATUS_LOCKED.equals(item.getStatus())) {
            return false;
        }

        java.util.Set<Long> existing = payrollRunItemCategoryRepository
                .findByPayrollRunItemIdWithWorkCodeCategory(item.getId()).stream()
                .map(c -> c.getWorkCodeCategory().getId())
                .collect(java.util.stream.Collectors.toSet());

        java.util.Map<Long, Integer> monthlyMinutes = monthlyReportCategoryRepository
                .findByMonthlyReportIdWithCategory(mr.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> c.getWorkCodeCategory().getId(),
                        c -> safe(c.getTotalMinutes()),
                        (a, b) -> a));

        java.util.Set<Long> wanted = new java.util.HashSet<>(monthlyMinutes.keySet());
        if (scope != null) {
            wanted.addAll(scope.allowedWorkCategoryIds());
        }
        wanted.removeAll(existing);
        if (wanted.isEmpty()) {
            return false;
        }

        List<com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory> categories =
                workCodeCategoryRepository.findAllById(wanted);

        boolean addedWithActivity = false;
        for (var category : categories) {
            payrollRunItemCategoryRepository.save(newItemCategory(item, category));
            if (monthlyMinutes.getOrDefault(category.getId(), 0) > 0) {
                addedWithActivity = true;
            }
        }

        log.info("PayrollRunItem {}: added {} missing category row(s){}",
                item.getId(), categories.size(),
                addedWithActivity ? " — recalculating, one of them has activity" : "");
        return addedWithActivity;
    }

    /** Every applied, unarchived correction for this item, as one signed number. */
    private int payableMinuteCorrectionFor(Long itemId) {
        return timeAdjustmentRepository.sumPayableMinutesFor(itemId);
    }

    /**
     * Put the item's single manual time correction at {@code minutes}.
     *
     * <p>One row, because that is what the one integer on the item could express
     * and nothing more; a second cause gets its own row through the dedicated
     * API, not through this patch field. Zero means "there is no correction", so
     * the row is archived rather than stored as a zero the database would reject
     * anyway.
     *
     * <p>The reason is compulsory when the category says so, which is the whole
     * reason the table exists. Rejected here with a clear message rather than
     * left to the trigger, which would surface as a raw SQL error.
     */
    private void syncManualTimeCorrection(PayrollRunItem item, int minutes, String reason) {
        PayrollTimeAdjustmentCategory category = timeAdjustmentCategoryRepository
                .findByCode(PayrollTimeAdjustmentCategory.CODE_MANUAL_CORRECTION)
                .orElseThrow(() -> new IncompletePayrollConfigurationException(
                        "Kategorija korekcije vremena \"MANUAL_CORRECTION\" ne postoji."));

        PayrollTimeAdjustment existing = timeAdjustmentRepository
                .findByItemIdWithCategory(item.getId()).stream()
                .filter(t -> t.getCategory().getId().equals(category.getId()))
                .findFirst().orElse(null);

        if (minutes == 0) {
            if (existing != null) {
                existing.setIsApplied(false);
                existing.setArchivedAt(OffsetDateTime.now());
                existing.setEditedBy(currentUserService.getCurrentUserId());
                existing.setEditedAt(OffsetDateTime.now());
                timeAdjustmentRepository.save(existing);
            }
            // The in-flight object, so this request's own response is right. It is
            // not persisted — the archived row above is what says there is no
            // correction any more.
            item.setManualAdjustedMinutes(0);
            return;
        }

        if (minutes < 0 && !Boolean.TRUE.equals(category.getAllowNegative())) {
            throw new ConflictException("Korekcija vremena ne može biti negativna.");
        }
        if (minutes > 0 && !Boolean.TRUE.equals(category.getAllowPositive())) {
            throw new ConflictException("Korekcija vremena ne može biti pozitivna.");
        }

        boolean changed = existing == null || !Integer.valueOf(minutes).equals(existing.getMinutes());
        String effectiveReason = reason != null && !reason.isBlank()
                ? reason
                : (existing != null ? existing.getReason() : null);

        if (Boolean.TRUE.equals(category.getRequireReason())
                && changed
                && (effectiveReason == null || effectiveReason.isBlank())) {
            throw new ConflictException("Razlog je obavezan za korekciju radnog vremena.");
        }

        if (existing == null) {
            timeAdjustmentRepository.save(PayrollTimeAdjustment.builder()
                    .payrollRunItem(item)
                    .category(category)
                    .systemMinutes(0)
                    .minutes(minutes)
                    .hasManualInput(true)
                    .reason(effectiveReason)
                    .isApplied(true)
                    .createdBy(currentUserService.getCurrentUserId())
                    .build());
        } else if (changed
                || !java.util.Objects.equals(effectiveReason, existing.getReason())
                || !Boolean.TRUE.equals(existing.getIsApplied())
                || existing.getArchivedAt() != null) {
            existing.setMinutes(minutes);
            existing.setReason(effectiveReason);
            existing.setHasManualInput(true);
            existing.setIsApplied(true);
            existing.setArchivedAt(null);
            existing.setEditedBy(currentUserService.getCurrentUserId());
            existing.setEditedAt(OffsetDateTime.now());
            timeAdjustmentRepository.save(existing);
        }
        // ELSE: THE SAME CORRECTION, SENT AGAIN — nothing is written.
        //
        // Saving anyway would still move edited_by and edited_at, so the row would
        // change and its audit trigger would record an entry. Re-opening a payroll
        // and pressing save would then read as an edit to somebody's paid time.
        // The trail is for decisions, and re-confirming is not one.
        item.setManualAdjustedMinutes(minutes);
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

        if (item.getTotalPayrollMinutes() == null)      item.setTotalPayrollMinutes(0);

        if (item.getStatus() == null)                   item.setStatus(STATUS_DRAFT);

        if (item.getHourlyRate() == null)               item.setHourlyRate(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getHourlyRateSystem() == null)         item.setHourlyRateSystem(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        if (item.getHourlyRateOverridden() == null)     item.setHourlyRateOverridden(false);


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
