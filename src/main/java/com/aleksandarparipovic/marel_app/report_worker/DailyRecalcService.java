package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.analytics.AnalyticsFactSyncService;
import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategory;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.notification.ReportNotificationService;
import com.aleksandarparipovic.marel_app.recalc_queue.DailyRecalcQueue;
import com.aleksandarparipovic.marel_app.recalc_queue.DailyRecalcQueueRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingRepository;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolution;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolutionService;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.WorkLogCompensationSnapshot;
import com.aleksandarparipovic.marel_app.work_log.WorkLogPerformanceCalculator;
import com.aleksandarparipovic.marel_app.work_log.interval.ShiftIntervalResolver;
import com.aleksandarparipovic.marel_app.work_log.interval.WorkIntervalCalculator;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyRecalcService {

    private static final String MAPPING_MULTIPLE_MACHINES_BONUS = "MULTIPLE_MACHINES_BONUS";
    private static final String MAPPING_NIGHT_SHIFT_BONUS = "NIGHT_SHIFT_BONUS";
    private static final String MAPPING_WEEKEND_BONUS = "WEEKEND_BONUS";
    private static final int WEEKEND_BONUS_MIN_MINUTES = 180;
    private static final int MAX_ERROR_LENGTH = 255;

    private final DailyRecalcQueueRepository queueRepo;
    private final DailyReportRepository reportRepo;
    private final DailyReportCategoryRepository categoryRepo;
    private final WorkLogRepository workLogRepo;
    private final RecalcQueueService recalcQueueService;
    private final ReportNotificationService notificationService;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final AppSettingService appSettingService;
    private final RecalcWorkerProperties properties;
    private final MeterRegistry meterRegistry;
    private final EmployeeRepository employeeRepository;
    private final EmployeeRecordService employeeRecordService;
    private final WorkShiftRepository workShiftRepository;
    private final WorkCodeCategoryMappingRepository mappingRepository;
    private final ShiftRepository shiftRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkLogPerformanceCalculator performanceCalculator;
    private final AnalyticsFactSyncService analyticsFactSyncService;
    private final WorkIntervalCalculator intervalCalculator;
    private final ShiftIntervalResolver intervalResolver;
    private final WorkCategoryResolutionService resolutionService;
    private final WorkLogCompensationSnapshot compensationSnapshot;

    public void processJob(Long jobId) {
        long startedAt = System.nanoTime();
        DailyRecalcQueue snapshot = queueRepo.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Daily recalc job not found: " + jobId));

        if (!"IN_PROGRESS".equals(snapshot.getStatus())) {
            return;
        }

        int claimedVersion = snapshot.getVersion() == null ? 0 : snapshot.getVersion();
        Long workShiftId = snapshot.getWorkShift().getId();
        Long employeeId = snapshot.getEmployee().getId();
        LocalDate workDate = snapshot.getWorkDate();

        // Heavy reads are executed before the write transaction to reduce lock hold time.
        List<WorkLog> logs = workLogRepo.findActiveLogsWithRefsForShift(workShiftId);

        Boolean processed = transactionTemplate.execute(status -> processJobWritePhase(
                jobId,
                claimedVersion,
                workShiftId,
                employeeId,
                workDate,
                logs,
                startedAt
        ));

        if (!Boolean.TRUE.equals(processed)) {
            return;
        }
    }

    private boolean processJobWritePhase(Long jobId,
                                          int claimedVersion,
                                          Long workShiftId,
                                          Long employeeId,
                                          LocalDate workDate,
                                          List<WorkLog> logs,
                                          long startedAt) {
        WorkShift workShift = workShiftRepository.findById(workShiftId)
                .orElseThrow(() -> new IllegalStateException("Work shift not found: " + workShiftId));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalStateException("Employee not found: " + employeeId));

        EmployeeRecord monthlyRecord = employeeRecordService.getOrCreateMonthlyRecord(employeeId, workDate);
        if (workShift.getEmployeeRecord() == null
                || !monthlyRecord.getId().equals(workShift.getEmployeeRecord().getId())) {
            workShift.setEmployeeRecord(monthlyRecord);
            workShiftRepository.save(workShift);
        }

        DailyReport report = reportRepo.findByWorkShiftId(workShift.getId())
                .orElseGet(() -> DailyReport.builder()
                        .employee(employee)
                        .workDate(workDate)
                        .workShift(workShift)
                        .calcVersion(0)
                        .version(0)
                        .createdAt(OffsetDateTime.now())
                        .build());

        if (report.getId() == null) {
            // Persist once to guarantee stable FK target for full category rebuilds.
            report = reportRepo.saveAndFlush(report);
        }

        // Final stale guard right before write: if version changed while computing, reschedule.
        DailyRecalcQueue locked = queueRepo.findByIdForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException("Daily recalc job not found after claim: " + jobId));

        int latestVersion = locked.getVersion() == null ? 0 : locked.getVersion();
        if (!"IN_PROGRESS".equals(locked.getStatus()) || latestVersion != claimedVersion) {
            locked.setStatus("PENDING");
            locked.setClaimedAt(null);
            locked.setClaimedBy(null);
            locked.setRequestedAt(OffsetDateTime.now());
            queueRepo.save(locked);
            log.debug("Daily job {} rescheduled due to newer version {} > {}", jobId, latestVersion, claimedVersion);
            meterRegistry.counter("recalc.jobs.rescheduled", "type", "daily").increment();
            return false;
        }

        // ── Compensation scheme, resolved ONCE for this employee and work date ──
        // Two queries for the whole shift regardless of how many logs it has.
        // Everything below reads the resolution or the snapshot it wrote; nothing
        // in this class asks whether an employee is a foreigner.
        WorkCategoryResolutionService.ResolutionContext schemeContext =
                resolutionService.contextFor(employeeId, workDate);
        Map<Long, WorkCategoryResolution> resolutionByLogId =
                refreshCompensationSnapshots(logs, schemeContext);

        // Resolve bonus category mappings applicable for this shift
        Set<String> applicableTypes = resolveApplicableMappingTypes(workShift, workDate, employeeId);
        List<WorkCodeCategoryMapping> mappings = applicableTypes.isEmpty()
                ? List.of()
                : mappingRepository.findActiveByTypesAndDate(applicableTypes, workDate);

        // Night and weekend remaps are kept separate and applied in fixed order
        // (night first, then weekend) so chained conversions work, e.g.
        // 13 →(night) 9 →(weekend) 10.
        Map<Long, WorkCodeCategory> nightRemap = new HashMap<>();
        Map<Long, WorkCodeCategory> weekendRemap = new HashMap<>();
        Set<Long> plSourceIds = new HashSet<>();
        WorkCodeCategory plbCategory = null;

        for (WorkCodeCategoryMapping m : mappings) {
            switch (m.getMappingType()) {
                case MAPPING_MULTIPLE_MACHINES_BONUS -> {
                    plSourceIds.add(m.getSourceCategory().getId());
                    if (plbCategory == null) {
                        plbCategory = m.getTargetCategory();
                    }
                }
                case MAPPING_NIGHT_SHIFT_BONUS ->
                        nightRemap.put(m.getSourceCategory().getId(), m.getTargetCategory());
                case MAPPING_WEEKEND_BONUS ->
                        weekendRemap.put(m.getSourceCategory().getId(), m.getTargetCategory());
                default -> { /* unknown mapping type: ignore */ }
            }
        }

        if (report.getId() != null) {
            categoryRepo.deleteAllByDailyReportId(report.getId());
        }

        // Coefficient per base category, filled in by buildCategories. Only the
        // rows the compensation scheme actually remapped appear here; everything
        // else keeps using the category's own multiplier exactly as before.
        Map<Long, BigDecimal> schemeCoefficientByCategory = new HashMap<>();

        List<DailyReportCategory> categories = buildCategories(
                logs, report, nightRemap, weekendRemap, plSourceIds, plbCategory,
                resolutionByLogId, schemeCoefficientByCategory);
        if (!categories.isEmpty()) {
            categoryRepo.saveAll(categories);
        }

        // Covered time and verified time both come from the shared interval engine,
        // computed over the shift's raw logs rather than the category rows — category
        // rows have already lost the interval boundaries needed to union overlaps.
        WorkIntervalCalculator.VerifiedTime verified = intervalCalculator.computeVerifiedTime(
                intervalResolver.toIntervals(logs),
                intervalResolver.resolvePlbCoefficient(workDate));

        Integer previousBonusEligibleMinutes = report.getBonusEligibleMinutes();
        String reportSignatureBefore = reportContentSignature(report);
        fillDailyTotals(report, categories, workShift, employee, workDate, verified,
                schemeCoefficientByCategory);
        reportRepo.saveAndFlush(report);
        categoryRepo.flush();
        boolean reportChanged = !reportSignatureBefore.equals(reportContentSignature(report));

        // Sync the analytics fact table from the same already-loaded logs list. Runs inside
        // this same transaction, so a sync failure rolls back with the rest of the recalc and
        // inherits the existing recalc-queue retry semantics for free.
        analyticsFactSyncService.upsertFactsForShift(workShift, logs);

        boolean wasEligible = previousBonusEligibleMinutes != null
                && previousBonusEligibleMinutes >= WEEKEND_BONUS_MIN_MINUTES;
        boolean isEligible = report.getBonusEligibleMinutes() >= WEEKEND_BONUS_MIN_MINUTES;
        if (wasEligible != isEligible) {
            recheckWeekendBonusForWeek(workDate, employeeId);
        }

        // Persist the reversible bonus-effective category on the shift and its logs
        // (original work_code_category_id is never overwritten).
        updateWorkShiftEffectiveCategory(workShift, nightRemap, weekendRemap);
        applyEffectiveWorkCodes(logs, workShiftId, nightRemap, weekendRemap, plSourceIds);

        locked.setStatus("DONE");
        locked.setProcessedAt(OffsetDateTime.now());
        locked.setClaimedAt(null);
        locked.setClaimedBy(null);
        queueRepo.save(locked);

        if (locked.getRequestedAt() != null) {
            meterRegistry.timer("recalc.queue.latency", "type", "daily")
                    .record(Duration.between(locked.getRequestedAt(), locked.getProcessedAt()));
        }

        // Skip the daily WS notification, the monthly recalc and its notification when a
        // cascade recheck (e.g. WEEKLY_BONUS_RECHECK) recomputed an identical report — that
        // is the common case and was the main source of redundant work and reload churn.
        // Direct user edits always notify so the frontend spinner clears even on a no-op edit.
        String reason = locked.getReason();
        boolean directEdit = "WORK_LOG_MUTATION".equals(reason) || "WORK_SHIFT_UPDATE".equals(reason);
        if (directEdit || reportChanged) {
            recalcQueueService.enqueueMonthlyJob(employee, workDate.getYear(), workDate.getMonthValue(), "DAILY_RECALC");
            eventPublisher.publishEvent(new DailyRecalcRequestedEvent(DailyRecalcRequestedEvent.Type.MONTHLY));
            notificationService.sendDailyReportUpdate(employee.getId(), workDate, workShift.getId());
        }

        meterRegistry.counter("recalc.jobs.processed", "type", "daily").increment();
        meterRegistry.timer("recalc.job.duration", "type", "daily")
                .record(Duration.ofNanos(System.nanoTime() - startedAt));

        return true;
    }

    @Transactional
    public void markFailed(Long jobId, String errorMessage) {
        queueRepo.findById(jobId).ifPresent(job -> {
            int retries = job.getRetryCount() != null ? job.getRetryCount() : 0;
            int next = retries + 1;
            job.setRetryCount(next);
            job.setLastError(truncateError(errorMessage));
            job.setClaimedAt(null);
            job.setClaimedBy(null);

            if (next >= properties.getMaxRetry()) {
                job.setStatus("FAILED");
                job.setProcessedAt(OffsetDateTime.now());
                meterRegistry.counter("recalc.jobs.failed", "type", "daily").increment();
            } else {
                long backoffMs = computeBackoffMs(next);
                job.setStatus("PENDING");
                job.setRequestedAt(OffsetDateTime.now().plusNanos(backoffMs * 1_000_000));
                meterRegistry.counter("recalc.jobs.retry", "type", "daily").increment();
            }
            queueRepo.save(job);
        });
    }

    private long computeBackoffMs(int retryCount) {
        long baseMs = Math.max(1L, properties.getBaseBackoffMs());
        return Math.min(300_000L, baseMs * (1L << Math.min(retryCount, 10)));
    }

    // last_error is varchar(255); a long stack-trace message must be truncated or the
    // markFailed UPDATE itself fails and leaves the job stuck IN_PROGRESS.
    private String truncateError(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    // Signature of the report fields that drive the frontend daily view and the monthly
    // aggregation. Used to detect a no-op recompute so a cascade recheck can skip the WS
    // notification and the (expensive) monthly recalc when nothing actually changed.
    private String reportContentSignature(DailyReport r) {
        return safeInt(r.getTotalShiftMinutes()) + "|"
                + safeInt(r.getTotalWorkMinutes()) + "|"
                + safeInt(r.getTotalAbsencePaidMinutes()) + "|"
                + safeInt(r.getTotalAbsenceUnpaidMinutes()) + "|"
                + safeInt(r.getTotalSickLeavePaidMinutes()) + "|"
                + safeInt(r.getTotalSickLeaveUnpaidMinutes()) + "|"
                + safeInt(r.getTotalCompensatedMinutes()) + "|"
                + safeInt(r.getTotalApprovedMinutes()) + "|"
                + safeInt(r.getBonusEligibleMinutes()) + "|"
                + safeInt(r.getTotalQuantity()) + "|"
                + safeInt(r.getTotalScrap()) + "|"
                + sigDec(r.getTotalWeightedNormMinutes()) + "|"
                + sigDec(r.getTotalVerifiedMinutes()) + "|"
                + safeInt(r.getTotalPlMinutes()) + "|"
                + safeInt(r.getTotalPlbMinutes()) + "|"
                + sigDec(r.getPerformanceCoefficient()) + "|"
                + sigDec(r.getApprovedPerformanceCoefficient()) + "|"
                + safeInt(r.getMealsCount()) + "|"
                + r.getIsMealAllowed();
    }

    private String sigDec(BigDecimal value) {
        return value == null ? "_" : value.stripTrailingZeros().toPlainString();
    }

    // -------------------------------------------------------------------------
    // Bonus mapping resolution
    // -------------------------------------------------------------------------

    private Set<String> resolveApplicableMappingTypes(WorkShift workShift, LocalDate workDate, Long employeeId) {
        Set<String> types = new LinkedHashSet<>();
        // Always check: the overlap algorithm decides if PLB actually applies
        types.add(MAPPING_MULTIPLE_MACHINES_BONUS);
        if (isNightShift(workShift)) {
            types.add(MAPPING_NIGHT_SHIFT_BONUS);
        }
        if (isWeekendBonusEligible(workDate, employeeId)) {
            types.add(MAPPING_WEEKEND_BONUS);
        }
        return types;
    }

    private boolean isNightShift(WorkShift workShift) {
        Shift shift = workShift.getShift();
        if (shift != null && "III".equals(shift.getShiftCode())) {
            return true;
        }
        if (workShift.getStartAt() == null) return false;
        Optional<Shift> thirdShift = shiftRepository.findFirstByShiftCodeAndIsActiveTrue("III");
        if (thirdShift.isEmpty()) return false;
        LocalTime shiftStart = workShift.getStartAt().toLocalTime();
        return !shiftStart.isBefore(thirdShift.get().getStartTime());
    }

    private boolean isWeekendBonusEligible(LocalDate workDate, Long employeeId) {
        DayOfWeek day = workDate.getDayOfWeek();
        if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) return false;
        LocalDate weekStart = workDate.with(DayOfWeek.MONDAY);
        int missing = Optional.ofNullable(
                reportRepo.countPreviousDaysWithInsufficientBonusMinutes(employeeId, weekStart, workDate, WEEKEND_BONUS_MIN_MINUTES)
        ).orElse(0);
        return missing == 0;
    }

    // Called only when a day's bonus-eligible status just crossed the 180-minute threshold
    // (see the wasEligible/isEligible check at the call site) — NOT on every recalc, since
    // most edits don't change which side of 180 a day falls on. The weekend day(s) whose
    // eligibility window [Monday, day) includes this day may need WEEKEND_BONUS re-evaluated
    // in EITHER direction — isWeekendBonusEligible only runs when the weekend day itself is
    // recalculated, so a later crossing elsewhere in the week would otherwise leave its
    // already-computed remap stale. Targets are derived strictly from isWeekendBonusEligible's
    // own window definition so the trigger graph has no cycles:
    //   - Mon-Fri changes  -> recheck both Saturday [Mon,Sat) and Sunday [Mon,Sun)
    //   - Saturday changes -> recheck only Sunday (Sunday's window includes Saturday;
    //                         Saturday's own window does not include itself)
    //   - Sunday changes   -> recheck nothing (no window includes Sunday)
    // Without this asymmetry, Saturday and Sunday's own recalcs would keep re-triggering each
    // other indefinitely.
    private void recheckWeekendBonusForWeek(LocalDate workDate, Long employeeId) {
        DayOfWeek day = workDate.getDayOfWeek();
        if (day == DayOfWeek.SUNDAY) {
            return;
        }
        List<LocalDate> targets = new ArrayList<>();
        targets.add(workDate.with(DayOfWeek.SUNDAY));
        if (day != DayOfWeek.SATURDAY) {
            targets.add(workDate.with(DayOfWeek.SATURDAY));
        }
        List<WorkShift> weekendShifts = workShiftRepository.findByEmployee_IdAndWorkDateIn(employeeId, targets);
        for (WorkShift weekendShift : weekendShifts) {
            // Skip if already queued/running: re-enqueuing a PENDING/IN_PROGRESS job bumps its
            // version while it's mid-flight, which can race with that job's own completion and
            // cause it to keep getting rescheduled — this is what was producing the runaway
            // Saturday<->Sunday version-bump loop. The already-queued pass will see the same
            // (or by-then-current) data, so there is nothing for this extra trigger to add.
            if (queueRepo.existsByWorkShift_IdAndStatusIn(weekendShift.getId(), List.of("PENDING", "IN_PROGRESS"))) {
                continue;
            }
            recalcQueueService.enqueueDailyJob(weekendShift, "WEEKLY_BONUS_RECHECK");
        }
    }

    // -------------------------------------------------------------------------
    // Category building
    // -------------------------------------------------------------------------

    private List<DailyReportCategory> buildCategories(List<WorkLog> logs,
                                                       DailyReport report,
                                                       Map<Long, WorkCodeCategory> nightRemap,
                                                       Map<Long, WorkCodeCategory> weekendRemap,
                                                       Set<Long> plSourceIds,
                                                       WorkCodeCategory plbCategory,
                                                       Map<Long, WorkCategoryResolution> resolutionByLogId,
                                                       Map<Long, BigDecimal> schemeCoefficientByCategory) {
        List<WorkLog> filteredLogs = logs.stream()
                .filter(wl -> wl.getWorkCode() != null)
                .toList();

        List<WorkLog> plLogs = plSourceIds.isEmpty()
                ? List.of()
                : filteredLogs.stream()
                        .filter(wl -> plSourceIds.contains(wl.getWorkCode().getId()))
                        .toList();

        List<WorkLog> otherLogs = plSourceIds.isEmpty()
                ? filteredLogs
                : filteredLogs.stream()
                        .filter(wl -> !plSourceIds.contains(wl.getWorkCode().getId()))
                        .toList();

        List<DailyReportCategory> result = new ArrayList<>();

        // Non-PL logs: group by the category the base calculation uses.
        //
        // TWO INDEPENDENT RESOLUTIONS, BOTH KEYED ON THE SOURCE CATEGORY:
        //
        //   the contextual mapping chain (night then weekend) — unchanged, still
        //   computed from wl.getWorkCode(), and still persisted onto the log and
        //   the shift further below as the reversible bonus category;
        //
        //   the compensation scheme — which decides what the base row is worth.
        //
        // When the scheme remaps (a fixed-coefficient employee), the base row is
        // the scheme's effective category. When it does not — every standard
        // employee — the base row is the mapped category, exactly as before, so
        // standard payroll is untouched.
        //
        // The fixed coefficient therefore changes what the row is worth WITHOUT
        // deleting the night mapping: the mapping is still resolved from the
        // source category and still recorded on the log.
        Map<Long, WorkCodeCategory> baseCategoryById = new HashMap<>();
        Map<Long, List<WorkLog>> byBaseCategory = new HashMap<>();
        for (WorkLog wl : otherLogs) {
            WorkCodeCategory base = resolveBaseCategory(wl, nightRemap, weekendRemap, resolutionByLogId);
            baseCategoryById.put(base.getId(), base);
            byBaseCategory.computeIfAbsent(base.getId(), id -> new ArrayList<>()).add(wl);
        }

        for (Map.Entry<Long, List<WorkLog>> entry : byBaseCategory.entrySet()) {
            WorkCodeCategory category = baseCategoryById.get(entry.getKey());
            if (category != null) {
                recordSchemeCoefficient(entry.getValue(), category, resolutionByLogId, schemeCoefficientByCategory);
                result.add(buildCategoryEntry(entry.getValue(), category, report));
            }
        }

        // PL logs: split between PL (reduced) and PLB (triple-overlap portion)
        if (!plLogs.isEmpty()) {
            long plbMinutes = computeTripleOverlapMinutes(plLogs);
            int totalPlMinutes = plLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();

            Map<Long, List<WorkLog>> byPlSource = plLogs.stream()
                    .collect(Collectors.groupingBy(wl -> wl.getWorkCode().getId()));

            for (Map.Entry<Long, List<WorkLog>> entry : byPlSource.entrySet()) {
                List<WorkLog> catLogs = entry.getValue();
                WorkCodeCategory plCategory = catLogs.getFirst().getWorkCode();
                int catMinutes = catLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();
                // Distribute PLB reduction proportionally across PL source categories
                long reduction = totalPlMinutes > 0 ? plbMinutes * catMinutes / totalPlMinutes : 0;
                int plMinutes = (int) Math.max(0, catMinutes - reduction);
                result.add(buildPlCategoryEntry(catLogs, plCategory, report, plMinutes));
            }

            if (plbMinutes > 0 && plbCategory != null) {
                result.add(buildPlbCategoryEntry(plbCategory, report, (int) plbMinutes));
            }
        }

        return result;
    }

    /**
     * Re-resolve every log's compensation scheme and refresh its snapshot.
     *
     * <p>Runs before anything reads a coefficient, because the interval engine
     * and the category rows both consume the snapshot.
     *
     * <p>Only writes when a value actually changed — an unchanged recalc must not
     * produce an UPDATE, or the audit log fills with rows recording that nothing
     * happened.
     *
     * <p>A log whose category the scheme no longer allows is NOT rejected here.
     * Recalculation is not the place to refuse historical data: the work was
     * accepted when it was recorded, and failing the job would only wedge the
     * queue. It keeps its existing snapshot, and the condition is logged so
     * somebody can fix the rule or the log deliberately.
     */
    private Map<Long, WorkCategoryResolution> refreshCompensationSnapshots(
            List<WorkLog> logs,
            WorkCategoryResolutionService.ResolutionContext schemeContext) {

        Map<Long, WorkCategoryResolution> byLogId = new HashMap<>();
        for (WorkLog log : logs) {
            if (log.getWorkCode() == null) {
                continue;
            }
            WorkCategoryResolution resolution = schemeContext.resolveFor(log.getWorkCode());
            if (!resolution.allowed()) {
                logDisallowedHistoricalLog(log, schemeContext, resolution);
                continue;
            }
            byLogId.put(log.getId(), resolution);
            if (!compensationSnapshot.matches(log, resolution)) {
                compensationSnapshot.apply(log, resolution);
                workLogRepo.save(log);
            }
        }
        return byLogId;
    }

    private void logDisallowedHistoricalLog(WorkLog workLog,
                                            WorkCategoryResolutionService.ResolutionContext schemeContext,
                                            WorkCategoryResolution resolution) {
        log.warn("Work log {} uses work-code category {} which scheme {} no longer allows on {} ({});"
                        + " keeping its existing snapshot",
                workLog.getId(), workLog.getWorkCode().getCategoryNo(),
                schemeContext.scheme().getCode(), schemeContext.workDate(),
                resolution.resolutionReason());
    }

    /**
     * The category the base calculation groups this log under.
     *
     * <p>The scheme's effective category when the scheme remapped, otherwise the
     * contextual mapping chain's result — which is what this method returned
     * before compensation schemes existed, for every log.
     */
    private WorkCodeCategory resolveBaseCategory(WorkLog workLog,
                                                 Map<Long, WorkCodeCategory> nightRemap,
                                                 Map<Long, WorkCodeCategory> weekendRemap,
                                                 Map<Long, WorkCategoryResolution> resolutionByLogId) {
        WorkCategoryResolution resolution = resolutionByLogId.get(workLog.getId());
        if (resolution != null && resolution.isCategoryRemapped()) {
            WorkCodeCategory schemeEffective = workLog.getSchemeEffectiveWorkCode();
            if (schemeEffective != null) {
                return schemeEffective;
            }
            return workCodeCategoryRepository.findById(resolution.effectiveCategoryId())
                    .orElse(workLog.getWorkCode());
        }
        return resolveEffectiveCategory(workLog.getWorkCode(), nightRemap, weekendRemap);
    }

    /**
     * Record the coefficient for a base category the scheme remapped.
     *
     * <p>Nothing is recorded when the scheme did not remap, so
     * {@code fillDailyTotals} falls back to the category's own multiplier and
     * standard employees keep their exact previous numbers.
     *
     * <p>When two source categories with different overrides land on the same
     * effective category the result is minute-weighted, which is exact when they
     * agree (the normal case) and proportional when they do not.
     */
    private void recordSchemeCoefficient(List<WorkLog> categoryLogs,
                                         WorkCodeCategory baseCategory,
                                         Map<Long, WorkCategoryResolution> resolutionByLogId,
                                         Map<Long, BigDecimal> schemeCoefficientByCategory) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalMinutes = 0;
        boolean anyRemapped = false;

        for (WorkLog wl : categoryLogs) {
            WorkCategoryResolution resolution = resolutionByLogId.get(wl.getId());
            if (resolution == null || resolution.coefficient() == null) {
                return;
            }
            if (resolution.isCategoryRemapped()) {
                anyRemapped = true;
            }
            int minutes = safeInt(wl.getDurationMin());
            if (minutes <= 0) {
                continue;
            }
            weightedSum = weightedSum.add(resolution.coefficient().multiply(BigDecimal.valueOf(minutes)));
            totalMinutes += minutes;
        }

        if (!anyRemapped || totalMinutes <= 0) {
            return;
        }
        schemeCoefficientByCategory.put(
                baseCategory.getId(),
                weightedSum.divide(BigDecimal.valueOf(totalMinutes), 6, RoundingMode.HALF_UP));
    }

    // Sets the shift's bonus-effective category WITHOUT touching the original
    // work_code_category_id. NULL when no remap applies, so it reverts automatically.
    private void updateWorkShiftEffectiveCategory(WorkShift workShift,
                                                  Map<Long, WorkCodeCategory> nightRemap,
                                                  Map<Long, WorkCodeCategory> weekendRemap) {
        WorkCodeCategory original = workShift.getWorkCodeCategory();
        if (original == null) {
            workShift.setEffectiveWorkCodeCategory(null);
            return;
        }
        WorkCodeCategory effective = resolveEffectiveCategory(original, nightRemap, weekendRemap);
        workShift.setEffectiveWorkCodeCategory(
                effective.getId().equals(original.getId()) ? null : effective);
    }

    // Persists the bonus-effective category on each active work log of the shift.
    // The original work_code_category_id stays untouched. PL logs are skipped because
    // PL→PLB is a time-overlap split, not a per-log swap. Always clears first, so a log
    // reverts to its original category when the condition no longer holds.
    private void applyEffectiveWorkCodes(List<WorkLog> logs,
                                         Long workShiftId,
                                         Map<Long, WorkCodeCategory> nightRemap,
                                         Map<Long, WorkCodeCategory> weekendRemap,
                                         Set<Long> plSourceIds) {
        workLogRepo.clearEffectiveWorkCodeForShift(workShiftId);
        if (nightRemap.isEmpty() && weekendRemap.isEmpty()) {
            return;
        }
        for (WorkLog wl : logs) {
            if (wl.getWorkCode() == null) continue;
            if (plSourceIds.contains(wl.getWorkCode().getId())) continue;
            WorkCodeCategory effective = resolveEffectiveCategory(wl.getWorkCode(), nightRemap, weekendRemap);
            if (!effective.getId().equals(wl.getWorkCode().getId())) {
                workLogRepo.setEffectiveWorkCode(wl.getId(), effective);
            }
        }
    }

    // Applies the bonus category chain in fixed order: night shift first, then weekend.
    // Example: 13 →(night) 9 →(weekend) 10. Each step applies at most once.
    private WorkCodeCategory resolveEffectiveCategory(WorkCodeCategory original,
                                                      Map<Long, WorkCodeCategory> nightRemap,
                                                      Map<Long, WorkCodeCategory> weekendRemap) {
        WorkCodeCategory current = original;
        WorkCodeCategory afterNight = nightRemap.get(current.getId());
        if (afterNight != null) {
            current = afterNight;
        }
        WorkCodeCategory afterWeekend = weekendRemap.get(current.getId());
        if (afterWeekend != null) {
            current = afterWeekend;
        }
        return current;
    }

    private DailyReportCategory buildCategoryEntry(List<WorkLog> catLogs, WorkCodeCategory category, DailyReport report) {
        int totalMinutes = catLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();
        int totalQuantity = catLogs.stream().mapToInt(wl -> safeInt(wl.getQuantity())).sum();
        int totalScrap = catLogs.stream().mapToInt(wl -> safeInt(wl.getScrap())).sum();

        BigDecimal[] rates = computeWeightedRates(catLogs);
        BigDecimal performanceCoefficient = totalMinutes > 0
                ? rates[0].divide(BigDecimal.valueOf(totalMinutes), 6, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal approvedPerformanceCoefficient = totalMinutes > 0
                ? rates[1].divide(BigDecimal.valueOf(totalMinutes), 6, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalWeightedNormMinutes = BigDecimal.valueOf(totalMinutes)
                .multiply(approvedPerformanceCoefficient)
                .setScale(4, RoundingMode.HALF_UP);

        return DailyReportCategory.builder()
                .dailyReport(report)
                .workCodeCategory(category)
                .totalMinutes(totalMinutes)
                .totalPaidMinutes(totalMinutes)
                .totalQuantity(totalQuantity)
                .totalScrap(totalScrap)
                .totalWeightedNormMinutes(totalWeightedNormMinutes)
                .performanceCoefficient(performanceCoefficient)
                .approvedPerformanceCoefficient(approvedPerformanceCoefficient)
                .sourceType(category.getType() != null ? category.getType() : "WORK")
                .build();
    }

    private DailyReportCategory buildPlCategoryEntry(List<WorkLog> catLogs, WorkCodeCategory category,
                                                      DailyReport report, int reducedMinutes) {
        int totalQuantity = catLogs.stream().mapToInt(wl -> safeInt(wl.getQuantity())).sum();
        int totalScrap = catLogs.stream().mapToInt(wl -> safeInt(wl.getScrap())).sum();
        int originalMinutes = catLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();

        BigDecimal[] rates = computeWeightedRates(catLogs);
        BigDecimal performanceCoefficient = originalMinutes > 0
                ? rates[0].divide(BigDecimal.valueOf(originalMinutes), 6, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal approvedPerformanceCoefficient = originalMinutes > 0
                ? rates[1].divide(BigDecimal.valueOf(originalMinutes), 6, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalWeightedNormMinutes = BigDecimal.valueOf(reducedMinutes)
                .multiply(approvedPerformanceCoefficient)
                .setScale(4, RoundingMode.HALF_UP);

        return DailyReportCategory.builder()
                .dailyReport(report)
                .workCodeCategory(category)
                .totalMinutes(reducedMinutes)
                .totalPaidMinutes(reducedMinutes)
                .totalQuantity(totalQuantity)
                .totalScrap(totalScrap)
                .totalWeightedNormMinutes(totalWeightedNormMinutes)
                .performanceCoefficient(performanceCoefficient)
                .approvedPerformanceCoefficient(approvedPerformanceCoefficient)
                .sourceType(category.getType() != null ? category.getType() : "WORK")
                .build();
    }

    private DailyReportCategory buildPlbCategoryEntry(WorkCodeCategory plbCategory, DailyReport report, int plbMinutes) {
        // PLB: performance coefficient is 1.0, qty/scrap are 0
        BigDecimal coefficient = BigDecimal.ONE;
        BigDecimal totalWeightedNormMinutes = BigDecimal.valueOf(plbMinutes)
                .setScale(4, RoundingMode.HALF_UP);

        return DailyReportCategory.builder()
                .dailyReport(report)
                .workCodeCategory(plbCategory)
                .totalMinutes(plbMinutes)
                .totalPaidMinutes(plbMinutes)
                .totalQuantity(0)
                .totalScrap(0)
                .totalWeightedNormMinutes(totalWeightedNormMinutes)
                .performanceCoefficient(coefficient)
                .approvedPerformanceCoefficient(coefficient)
                .sourceType(plbCategory.getType() != null ? plbCategory.getType() : "WORK")
                .build();
    }

    private BigDecimal[] computeWeightedRates(List<WorkLog> logs) {
        BigDecimal weightedRate = BigDecimal.ZERO;
        BigDecimal weightedApprovedRate = BigDecimal.ZERO;
        for (WorkLog wl : logs) {
            int duration = safeInt(wl.getDurationMin());
            if (duration <= 0) continue;
            BigDecimal perfRate = performanceCalculator.calculatePerformanceRate(wl);
            BigDecimal approvedRate = perfRate.min(appSettingService.getMaxEfficiencyPercentAt(wl.getStartAt()));
            weightedRate = weightedRate.add(perfRate.multiply(BigDecimal.valueOf(duration)));
            weightedApprovedRate = weightedApprovedRate.add(approvedRate.multiply(BigDecimal.valueOf(duration)));
        }
        return new BigDecimal[]{weightedRate, weightedApprovedRate};
    }

    // Sweep-line algorithm: counts total seconds where ≥3 PL work logs overlap simultaneously.
    private long computeTripleOverlapMinutes(List<WorkLog> plLogs) {
        List<long[]> events = new ArrayList<>();
        for (WorkLog log : plLogs) {
            OffsetDateTime start = log.getStartAt();
            OffsetDateTime end = log.getEndAt();
            if (start == null || end == null || !start.isBefore(end)) continue;
            events.add(new long[]{start.toEpochSecond(), 1L});
            events.add(new long[]{end.toEpochSecond(), -1L});
        }
        if (events.isEmpty()) return 0;
        // Sort by time; at same time, end events (-1) before start events (+1) to avoid false overlaps
        events.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        long totalOverlapSeconds = 0;
        int active = 0;
        Long prevTime = null;
        for (long[] event : events) {
            long time = event[0];
            int change = (int) event[1];
            if (prevTime != null && prevTime < time && active >= 3) {
                totalOverlapSeconds += (time - prevTime);
            }
            active += change;
            prevTime = time;
        }
        return totalOverlapSeconds / 60L;
    }

    // -------------------------------------------------------------------------
    // Daily totals
    // -------------------------------------------------------------------------

    private void fillDailyTotals(DailyReport report,
                                  List<DailyReportCategory> categories,
                                  WorkShift workShift,
                                  Employee employee,
                                  LocalDate workDate,
                                  WorkIntervalCalculator.VerifiedTime verified,
                                  Map<Long, BigDecimal> schemeCoefficientByCategory) {
        // Sum of the per-category minutes. This is NOT the shift duration — overlapping
        // wall-clock time appears in it once per category — but it remains the weighting
        // denominator for the performance coefficients, whose category weights must
        // still add up to 1. Changing that denominator would silently move payroll
        // figures, which is out of scope here.
        int categoryMinutesTotal = categories.stream().mapToInt(c -> safeInt(c.getTotalMinutes())).sum();

        // The authoritative shift duration: the global union of the shift's intervals.
        int totalShiftMinutes = (int) verified.coveredMinutes();
        int totalWorkMinutes = categories.stream()
                .filter(c -> isType(c.getSourceType(), "WORK"))
                .mapToInt(c -> safeInt(c.getTotalMinutes()))
                .sum();
        int totalAbsencePaidMinutes = categories.stream()
                .filter(c -> isType(c.getSourceType(), "ABSENCE") && Boolean.TRUE.equals(c.getWorkCodeCategory().getIsPaid()))
                .mapToInt(c -> safeInt(c.getTotalMinutes()))
                .sum();
        int totalAbsenceUnpaidMinutes = categories.stream()
                .filter(c -> isType(c.getSourceType(), "ABSENCE") && !Boolean.TRUE.equals(c.getWorkCodeCategory().getIsPaid()))
                .mapToInt(c -> safeInt(c.getTotalMinutes()))
                .sum();
        int totalSickLeavePaidMinutes = categories.stream()
                .filter(c -> isType(c.getSourceType(), "SICK_LEAVE") && Boolean.TRUE.equals(c.getWorkCodeCategory().getIsPaid()))
                .mapToInt(c -> safeInt(c.getTotalMinutes()))
                .sum();
        int totalSickLeaveUnpaidMinutes = categories.stream()
                .filter(c -> isType(c.getSourceType(), "SICK_LEAVE") && !Boolean.TRUE.equals(c.getWorkCodeCategory().getIsPaid()))
                .mapToInt(c -> safeInt(c.getTotalMinutes()))
                .sum();

        int totalQuantity = categories.stream().mapToInt(c -> safeInt(c.getTotalQuantity())).sum();
        int totalScrap = categories.stream().mapToInt(c -> safeInt(c.getTotalScrap())).sum();
        BigDecimal totalWeightedNormMinutes = categories.stream()
                .map(DailyReportCategory::getTotalWeightedNormMinutes)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal performanceCoefficient = weightedCoefficient(categories, false, categoryMinutesTotal);
        BigDecimal approvedPerformanceCoefficient = weightedCoefficient(categories, true, categoryMinutesTotal);

        report.setEmployee(employee);
        report.setWorkDate(workDate);
        report.setWorkShift(workShift);
        report.setTotalShiftMinutes(totalShiftMinutes);
        report.setTotalWorkMinutes(totalWorkMinutes);
        report.setTotalAbsencePaidMinutes(totalAbsencePaidMinutes);
        report.setTotalAbsenceUnpaidMinutes(totalAbsenceUnpaidMinutes);
        report.setTotalSickLeavePaidMinutes(totalSickLeavePaidMinutes);
        report.setTotalSickLeaveUnpaidMinutes(totalSickLeaveUnpaidMinutes);
        report.setTotalCompensatedMinutes(0); // TO-DO
        int approvedMinutes = totalWeightedNormMinutes.setScale(0, RoundingMode.HALF_UP).intValue();
        report.setTotalApprovedMinutes(approvedMinutes);

        // Verified time is a separate quantity from the efficiency-weighted approved
        // minutes above: it weights each covered interval by the PL/PLB coefficient
        // from work_code_categories.norm_multiplier. The two are deliberately kept
        // apart so no coefficient is ever applied twice to the same minute.
        report.setTotalVerifiedMinutes(verified.verifiedMinutes());
        report.setTotalPlMinutes((int) verified.plMinutes());
        report.setTotalPlbMinutes((int) verified.plbMinutes());

        int bonusEligibleMinutes = 0;
        for (DailyReportCategory cat : categories) {
            if (isType(cat.getSourceType(), "WORK")) {
                // The scheme's coefficient when it remapped this row, otherwise
                // the category's own multiplier — byte-for-byte the previous
                // behaviour for every employee the scheme does not touch.
                BigDecimal multiplier = schemeCoefficientByCategory.getOrDefault(
                        cat.getWorkCodeCategory().getId(),
                        resolveMultiplierByCategory(cat.getWorkCodeCategory()));
                bonusEligibleMinutes += BigDecimal.valueOf(safeInt(cat.getTotalMinutes()))
                        .multiply(multiplier)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValue();
            }
        }
        report.setBonusEligibleMinutes(bonusEligibleMinutes);
        report.setTotalQuantity(totalQuantity);
        report.setTotalScrap(totalScrap);
        report.setTotalWeightedNormMinutes(totalWeightedNormMinutes.setScale(4, RoundingMode.HALF_UP));
        report.setPerformanceCoefficient(performanceCoefficient);
        report.setApprovedPerformanceCoefficient(approvedPerformanceCoefficient);
        report.setPerformanceRate(performanceCoefficient.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));
        report.setApprovedPerformanceRate(approvedPerformanceCoefficient.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));

        int affectsMealAllowanceMinutes = categories.stream()
                .filter(c -> Boolean.TRUE.equals(c.getWorkCodeCategory().getAffectsMealAllowance()))
                .mapToInt(c -> safeInt(c.getTotalMinutes()))
                .sum();
        int mealsCount = (affectsMealAllowanceMinutes + 240) / 480;
        report.setIsMealAllowed(mealsCount > 0);
        report.setMealsCount(mealsCount);
        report.setCalcVersion((report.getCalcVersion() != null ? report.getCalcVersion() : 0) + 1);
        report.setLastRecalculatedAt(OffsetDateTime.now());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BigDecimal weightedCoefficient(List<DailyReportCategory> categories, boolean approved, int totalShiftMinutes) {
        if (totalShiftMinutes <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (DailyReportCategory category : categories) {
            BigDecimal coefficient = approved
                    ? defaultDecimal(category.getApprovedPerformanceCoefficient())
                    : defaultDecimal(category.getPerformanceCoefficient());
            BigDecimal weight = BigDecimal.valueOf(safeInt(category.getTotalMinutes()))
                    .divide(BigDecimal.valueOf(totalShiftMinutes), 6, RoundingMode.HALF_UP);
            total = total.add(coefficient.multiply(weight));
        }
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveMultiplierByCategory(WorkCodeCategory category) {
        if (category == null || category.getNormMultiplier() == null) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(category.getNormMultiplier());
    }

    private boolean isType(String value, String expected) {
        return expected.equalsIgnoreCase(value);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
