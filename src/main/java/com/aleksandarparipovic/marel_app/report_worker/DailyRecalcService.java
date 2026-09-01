package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.analytics.AnalyticsFactSyncService;
import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceCategoryCodes;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecord;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecordRepository;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategory;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.employee.ProbationPolicy;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.notification.ReportNotificationService;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecordService;
import com.aleksandarparipovic.marel_app.recalc_queue.DailyRecalcQueue;
import com.aleksandarparipovic.marel_app.recalc_queue.DailyRecalcQueueRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingRepository;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingTypeRepository;
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
import java.util.LinkedHashMap;
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

    /**
     * The scale coefficients are compared and stored at.
     *
     * <p>Matches work_logs.norm_multiplier_snapshot and the report rows' own
     * column. Two rows must not split apart because one holds 1.1 and the other
     * 1.10, and BigDecimal equality — which is what a map key uses — would do
     * exactly that.
     */
    private static final int COEFFICIENT_SCALE = 2;

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
    private final ProbationPolicy probationPolicy;
    private final EmployeeRecordService employeeRecordService;
    private final WorkShiftRepository workShiftRepository;
    private final WorkCodeCategoryMappingRepository mappingRepository;
    private final WorkCodeCategoryMappingTypeRepository mappingTypeRepository;
    private final ShiftRepository shiftRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkLogPerformanceCalculator performanceCalculator;
    private final AnalyticsFactSyncService analyticsFactSyncService;
    private final OvertimeRecordService overtimeRecordService;
    private final AbsenceRecordRepository absenceRecordRepository;
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
        /*
         * AN ABSENCE LOG IS NOT WORK, AND IS NOT MEASURED AS ANY.
         *
         * Both NO and ND are written as work logs so a full day off shows on the
         * shift beside everything else. Fed into the aggregation, though, either
         * would be counted as time present with a coefficient of zero — and since
         * the monthly efficiency is totalWeightedNormMinutes / totalShiftMinutes,
         * one such day would drag a whole month's efficiency down. (On probation
         * it is worse and the other way: the probation rule credits every row at
         * 100 %, so the day would INFLATE the month instead.)
         *
         * It also keeps the overtime honest. Overtime is measured from the day's
         * covered minutes; an eight-hour absence counted as presence would earn
         * overtime on a day nobody worked.
         *
         * Removed here, once, rather than guarded at each of the four places that
         * would otherwise have to remember: the interval engine, the category
         * rows, the two coefficient denominators, and the analytics facts.
         *
         * The minutes are not lost. They reach total_absence_unpaid_minutes
         * through the absence record these logs mirror, which carries the same
         * span — see fillDailyTotals. Counting BOTH would report a full shift of
         * absence twice on a day that had one.
         */
        List<WorkLog> logs = workLogRepo.findActiveLogsWithRefsForShift(workShiftId).stream()
                .filter(wl -> wl.getWorkCode() == null
                        || !AbsenceCategoryCodes.isAbsenceLog(wl.getWorkCode().getCategoryNo()))
                .toList();

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

        /*
         * A WITHDRAWN SHIFT BUILDS NOTHING.
         *
         * Archiving already removes the daily report and requeues the month, so
         * this is the guard for every other way a job can arrive here — a work
         * log touched before the shift was taken back, a cascade from the weekend
         * recheck, a job that was already queued. Without it, any of those would
         * rebuild the report from the shift's own logs and quietly put the hours
         * back into the month.
         */
        if (workShift.getArchivedAt() != null) {
            reportRepo.findByWorkShiftId(workShift.getId()).ifPresent(stale -> {
                categoryRepo.deleteAllByDailyReportId(stale.getId());
                reportRepo.delete(stale);
            });

            queueRepo.findByIdForUpdate(jobId).ifPresent(job -> {
                job.setStatus("DONE");
                job.setProcessedAt(OffsetDateTime.now());
                job.setClaimedAt(null);
                job.setClaimedBy(null);
                queueRepo.save(job);
            });

            recalcQueueService.enqueueMonthlyJob(employee, workDate.getYear(), workDate.getMonthValue(),
                    "WORK_SHIFT_ARCHIVED");
            log.info("Daily job {} skipped: work shift {} is archived", jobId, workShiftId);
            return true;
        }

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
                        // Zeroed explicitly, as DailyReportService.create does. The
                        // columns are NOT NULL with a database DEFAULT, but Hibernate
                        // names every mapped column in the INSERT, so an unset field
                        // is sent as an explicit NULL and the default never applies —
                        // the row is refused. fillDailyTotals overwrites all of these
                        // moments later; they exist so the first save can happen at all.
                        .totalShiftMinutes(0)
                        .totalWorkMinutes(0)
                        .totalAbsencePaidMinutes(0)
                        .totalAbsenceUnpaidMinutes(0)
                        .totalSickLeavePaidMinutes(0)
                        .totalSickLeaveUnpaidMinutes(0)
                        .totalCompensatedMinutes(0)
                        .totalApprovedMinutes(0)
                        .bonusEligibleMinutes(0)
                        .totalQuantity(0)
                        .totalScrap(0)
                        .totalWeightedNormMinutes(BigDecimal.ZERO)
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

        // Resolve bonus category mappings applicable for this shift.
        //
        // ORDER MATTERS AND IS THE BUSINESS RULE: contextual mapping FIRST, then
        // the compensation-scheme rule on the mapping's result. The scheme has
        // the last word on which category the pay row lands on, which is why the
        // rule set has to cover the mapping TARGETS (JB, DB, GB, ZB, L3, LP3,
        // PLB) and not only the categories a user can select.
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

        // ── Compensation scheme, resolved ONCE for this employee and work date ──
        // Two queries for the whole shift regardless of how many logs it has.
        // Nothing in this class asks whether an employee is a foreigner.
        WorkCategoryResolutionService.ResolutionContext schemeContext =
                resolutionService.contextFor(employeeId, workDate);

        // The log's own snapshot stays keyed on the SOURCE category: it records
        // what the entered work was worth, and it is what ShiftIntervalResolver
        // weights verified minutes by — the same quantity the source multiplier
        // drove before compensation schemes existed.
        Map<Long, WorkCategoryResolution> resolutionByLogId =
                refreshCompensationSnapshots(logs, schemeContext);

        if (report.getId() != null) {
            categoryRepo.deleteAllByDailyReportId(report.getId());
        }

        // Resolved ONCE for the whole shift, from the shift's WORK DATE. Work done
        // on probation is credited at 100 % however it measured — and the work
        // date, not each log's own start, is what decides it, or a night shift
        // starting on the last day of probation would be split across the boundary.
        boolean onProbation = probationPolicy.isOnProbation(employeeId, workDate);

        List<DailyReportCategory> categories = new ArrayList<>(buildCategories(
                logs, report, nightRemap, weekendRemap, plSourceIds, plbCategory,
                schemeContext, resolutionByLogId, onProbation));

        /*
         * THE ABSENCE IS A CATEGORY ROW TOO, or the payroll has nothing to show.
         *
         * daily_report_categories → monthly_report_categories →
         * payroll_run_item_categories is the whole path a category takes to a
         * payslip. Absences are not work logs, so nothing put them on it, and two
         * hours missed showed up as a smaller total with no line saying why.
         * They are built here instead, straight from the absence records.
         *
         * They carry no coefficient and no quantity: an absence is time, not
         * output. fillDailyTotals keeps them out of the efficiency denominator
         * for the same reason — see absenceMinutesOf.
         */
        categories.addAll(buildAbsenceCategories(workShift.getId(), report));

        // Recorded so a 100 % shift can say WHY it is 100 %: an employee who
        // genuinely hit the norm exactly and one who was on probation are
        // otherwise identical in the data.
        report.setWasProbation(onProbation);
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
        fillDailyTotals(report, categories, workShift, employee, workDate, verified);
        reportRepo.saveAndFlush(report);
        categoryRepo.flush();
        boolean reportChanged = !reportSignatureBefore.equals(reportContentSignature(report));

        // Sync the analytics fact table from the same already-loaded logs list. Runs inside
        // this same transaction, so a sync failure rolls back with the rest of the recalc and
        // inherits the existing recalc-queue retry semantics for free.
        analyticsFactSyncService.upsertFactsForShift(workShift, logs);

        /*
         * THE DAY'S OVERTIME, once the report it is measured from exists.
         *
         * Measured over the whole DAY rather than this shift: eight hours in the
         * first shift and eight in the third is eight hours of overtime, and
         * neither shift on its own says so. Which is also why it is recomputed
         * here on every shift of the day — each one changes the day's total.
         *
         * A change is what the month's allocation waits for. A day whose overtime
         * did NOT move must not enqueue anything, or the monthly job and this one
         * would keep handing work back to each other.
         */
        boolean overtimeChanged = overtimeRecordService.refreshForDay(employee, workDate);

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
        if (directEdit || reportChanged || overtimeChanged) {
            recalcQueueService.enqueueMonthlyJob(employee, workDate.getYear(), workDate.getMonthValue(),
                    overtimeChanged ? "OVERTIME_CHANGED" : "DAILY_RECALC");
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
        // Probation withholds whichever remaps say so — WEEKEND_BONUS today.
        // Removed here rather than never added, so the registry decides which
        // ones and this method keeps saying only WHEN each context applies.
        if (probationPolicy.isOnProbation(employeeId, workDate)) {
            types.removeAll(mappingTypeRepository.findCodesWithheldDuringProbation());
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
        List<WorkShift> weekendShifts = workShiftRepository.findByEmployee_IdAndWorkDateInAndArchivedAtIsNull(employeeId, targets);
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

    /**
     * One row of a daily report: a category AT ONE COEFFICIENT.
     *
     * <p>The coefficient is in the key because it is no longer a property of the
     * category alone. A supervisor may type one over on a single operation, and
     * four hours of category J can be two at 1.10 and two at 1.20 — two rows,
     * each priced by its own number, rather than one row and a lie about which.
     *
     * <p>Scale is normalised so 1.1 and 1.10 are one key rather than two.
     */
    private record CategoryRowKey(Long categoryId, BigDecimal coefficient) {}

    /** Rows group by value, not by how the value happens to be written. */
    private static BigDecimal coefficientKey(BigDecimal coefficient) {
        return (coefficient == null ? BigDecimal.ONE : coefficient)
                .setScale(COEFFICIENT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * What this row is calculated at: what somebody TYPED on the operation, and
     * otherwise what the scheme resolved for the row's category.
     *
     * <p>The typed value is read from the log rather than from its snapshot on
     * purpose. The snapshot resolves the log's SOURCE category, while a row may
     * have been remapped on the way here (night, weekend, scheme) — so the
     * resolved base has to come from the row, and only the override from the log.
     */
    private BigDecimal rowCoefficient(WorkLog log,
                                      WorkCategoryResolution rowResolution,
                                      WorkCodeCategory finalCategory) {
        if (log != null && log.getNormMultiplierManual() != null) {
            return coefficientKey(log.getNormMultiplierManual());
        }
        return defaultCoefficient(rowResolution, finalCategory);
    }

    /** What the row would be calculated at if nobody had typed anything. */
    private BigDecimal defaultCoefficient(WorkCategoryResolution rowResolution,
                                          WorkCodeCategory finalCategory) {
        return coefficientKey(rowResolution != null && rowResolution.coefficient() != null
                ? rowResolution.coefficient()
                : resolveMultiplierByCategory(finalCategory));
    }

    private List<DailyReportCategory> buildCategories(List<WorkLog> logs,
                                                       DailyReport report,
                                                       Map<Long, WorkCodeCategory> nightRemap,
                                                       Map<Long, WorkCodeCategory> weekendRemap,
                                                       Set<Long> plSourceIds,
                                                       WorkCodeCategory plbCategory,
                                                       WorkCategoryResolutionService.ResolutionContext schemeContext,
                                                       Map<Long, WorkCategoryResolution> resolutionByLogId,
                                                       boolean onProbation) {
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

        // Non-PL logs: group by the category the pay row lands on.
        //
        // THE ORDER IS THE BUSINESS RULE:
        //
        //   1. the contextual mapping chain (night then weekend), from the
        //      SOURCE category — unchanged, and still persisted onto the log and
        //      the shift further below as the reversible bonus category;
        //   2. then the compensation-scheme rule, applied to WHAT THE MAPPING
        //      PRODUCED. The scheme has the last word.
        //
        // A standard employee has no rules at all, so step 2 returns the mapped
        // category with its own multiplier — byte-for-byte the previous
        // behaviour. A fixed-coefficient employee collapses onto one category at
        // coefficient 1, whichever shift or trade they worked, and whether or
        // not a night mapping fired on the way.
        // LinkedHashMap: the row order a shift produces must not depend on hash
        // order, or two recalculations of the same day write the same rows in a
        // different sequence and every signature comparison sees a change.
        Map<CategoryRowKey, WorkCodeCategory> finalCategoryByRow = new LinkedHashMap<>();
        Map<CategoryRowKey, BigDecimal> defaultByRow = new LinkedHashMap<>();
        Map<CategoryRowKey, List<WorkLog>> byRow = new LinkedHashMap<>();

        for (WorkLog wl : otherLogs) {
            WorkCodeCategory mapped = resolveEffectiveCategory(wl.getWorkCode(), nightRemap, weekendRemap);
            WorkCategoryResolution rowResolution = schemeContext.resolveFor(mapped);
            WorkCodeCategory finalCategory = categoryOf(rowResolution, mapped);

            CategoryRowKey key = new CategoryRowKey(
                    finalCategory.getId(),
                    rowCoefficient(wl, rowResolution, finalCategory));

            finalCategoryByRow.putIfAbsent(key, finalCategory);
            defaultByRow.putIfAbsent(key, defaultCoefficient(rowResolution, finalCategory));
            byRow.computeIfAbsent(key, k -> new ArrayList<>()).add(wl);
        }

        for (Map.Entry<CategoryRowKey, List<WorkLog>> entry : byRow.entrySet()) {
            WorkCodeCategory category = finalCategoryByRow.get(entry.getKey());
            if (category != null) {
                result.add(buildCategoryEntry(entry.getValue(), category, report, onProbation,
                        entry.getKey().coefficient(), defaultByRow.get(entry.getKey())));
            }
        }

        // PL logs: split between PL (reduced) and PLB (triple-overlap portion).
        // The split is a time-overlap partition rather than a remap, so the two
        // resulting categories go through the scheme rule individually.
        if (!plLogs.isEmpty()) {
            long plbMinutes = computeTripleOverlapMinutes(plLogs);
            int totalPlMinutes = plLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();

            // Split by coefficient as well, for the same reason as above: a PL
            // category with one operation overridden is two rows, and the PLB
            // reduction is shared out over whatever rows result — the proportions
            // still add to the whole however finely the minutes are divided.
            Map<CategoryRowKey, List<WorkLog>> byPlSource = new LinkedHashMap<>();
            Map<CategoryRowKey, WorkCodeCategory> plFinalByRow = new LinkedHashMap<>();
            Map<CategoryRowKey, BigDecimal> plDefaultByRow = new LinkedHashMap<>();

            for (WorkLog wl : plLogs) {
                WorkCodeCategory plCategory = wl.getWorkCode();
                WorkCategoryResolution plResolution = schemeContext.resolveFor(plCategory);
                WorkCodeCategory plFinal = categoryOf(plResolution, plCategory);

                CategoryRowKey key = new CategoryRowKey(
                        plFinal.getId(), rowCoefficient(wl, plResolution, plFinal));

                plFinalByRow.putIfAbsent(key, plFinal);
                plDefaultByRow.putIfAbsent(key, defaultCoefficient(plResolution, plFinal));
                byPlSource.computeIfAbsent(key, k -> new ArrayList<>()).add(wl);
            }

            for (Map.Entry<CategoryRowKey, List<WorkLog>> entry : byPlSource.entrySet()) {
                List<WorkLog> catLogs = entry.getValue();
                int catMinutes = catLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();
                // Distribute PLB reduction proportionally across PL source categories
                long reduction = totalPlMinutes > 0 ? plbMinutes * catMinutes / totalPlMinutes : 0;
                int plMinutes = (int) Math.max(0, catMinutes - reduction);

                result.add(buildPlCategoryEntry(catLogs, plFinalByRow.get(entry.getKey()), report,
                        plMinutes, onProbation, entry.getKey().coefficient(),
                        plDefaultByRow.get(entry.getKey())));
            }

            if (plbMinutes > 0 && plbCategory != null) {
                WorkCategoryResolution plbResolution = schemeContext.resolveFor(plbCategory);
                WorkCodeCategory plbFinal = categoryOf(plbResolution, plbCategory);
                // No log of its own: PLB is the overlap between logs, so there is
                // nothing for anybody to have typed a coefficient on.
                BigDecimal plbCoefficientValue = rowCoefficient(null, plbResolution, plbFinal);
                result.add(buildPlbCategoryEntry(plbFinal, report, (int) plbMinutes,
                        plbCoefficientValue, plbCoefficientValue));
            }
        }

        // Several inputs can now legitimately land on the same final category —
        // a fixed-coefficient employee's ordinary work, their PL work and the PLB
        // portion all collapse onto S. daily_report_categories is UNIQUE on
        // (daily_report_id, work_code_category_id), so they have to be merged
        // rather than saved as separate rows.
        return mergeByCategory(result);
    }

    /** The final category: the scheme's effective one, or the input when it did not remap. */
    private WorkCodeCategory categoryOf(WorkCategoryResolution resolution, WorkCodeCategory fallback) {
        if (resolution == null || !resolution.isCategoryRemapped()) {
            return fallback;
        }
        return workCodeCategoryRepository.findById(resolution.effectiveCategoryId()).orElse(fallback);
    }

    /**
     * Record the coefficient for a row the scheme remapped.
     *
     * <p>Nothing is recorded when it did not, so {@code fillDailyTotals} falls
     * back to the category's own multiplier and standard employees keep their
     * exact previous numbers.
     */
    /**
     * Fold rows that share a category AND a coefficient into one.
     *
     * <p>Minutes, quantities and weighted norm minutes add up. The performance
     * coefficients are re-derived as a minute-weighted average, which is what
     * they already are within a single row — merging two rows must not turn a
     * weighted average into an unweighted one.
     */
    private List<DailyReportCategory> mergeByCategory(List<DailyReportCategory> rows) {
        Map<CategoryRowKey, DailyReportCategory> byCategory = new LinkedHashMap<>();

        for (DailyReportCategory row : rows) {
            // Merging on the category alone would put two coefficients back into
            // one row and lose the very distinction the row exists to carry — and
            // the unique key would then reject the result anyway.
            CategoryRowKey key = new CategoryRowKey(
                    row.getWorkCodeCategory().getId(), coefficientKey(row.getNormMultiplier()));
            DailyReportCategory existing = byCategory.get(key);
            if (existing == null) {
                byCategory.put(key, row);
                continue;
            }

            int minutesA = safeInt(existing.getTotalMinutes());
            int minutesB = safeInt(row.getTotalMinutes());
            int minutesTotal = minutesA + minutesB;

            existing.setTotalMinutes(minutesTotal);
            existing.setTotalPaidMinutes(safeInt(existing.getTotalPaidMinutes()) + safeInt(row.getTotalPaidMinutes()));
            existing.setTotalQuantity(safeInt(existing.getTotalQuantity()) + safeInt(row.getTotalQuantity()));
            existing.setTotalScrap(safeInt(existing.getTotalScrap()) + safeInt(row.getTotalScrap()));
            existing.setTotalWeightedNormMinutes(
                    defaultDecimal(existing.getTotalWeightedNormMinutes())
                            .add(defaultDecimal(row.getTotalWeightedNormMinutes())));

            existing.setPerformanceCoefficient(weightedMerge(
                    existing.getPerformanceCoefficient(), minutesA,
                    row.getPerformanceCoefficient(), minutesB, minutesTotal));
            existing.setApprovedPerformanceCoefficient(weightedMerge(
                    existing.getApprovedPerformanceCoefficient(), minutesA,
                    row.getApprovedPerformanceCoefficient(), minutesB, minutesTotal));

            // WORK wins: a merged row that contains any worked time is worked
            // time, and the daily totals split on this value.
            if ("WORK".equalsIgnoreCase(row.getSourceType())) {
                existing.setSourceType("WORK");
            }
        }

        return new ArrayList<>(byCategory.values());
    }

    private BigDecimal weightedMerge(BigDecimal a, int weightA, BigDecimal b, int weightB, int total) {
        if (total <= 0) {
            return defaultDecimal(a).max(defaultDecimal(b));
        }
        return defaultDecimal(a).multiply(BigDecimal.valueOf(weightA))
                .add(defaultDecimal(b).multiply(BigDecimal.valueOf(weightB)))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
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

    private DailyReportCategory buildCategoryEntry(List<WorkLog> catLogs, WorkCodeCategory category,
                                                   DailyReport report, boolean onProbation,
                                                   BigDecimal normMultiplier,
                                                   BigDecimal normMultiplierDefault) {
        int totalMinutes = catLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();
        int totalQuantity = catLogs.stream().mapToInt(wl -> safeInt(wl.getQuantity())).sum();
        int totalScrap = catLogs.stream().mapToInt(wl -> safeInt(wl.getScrap())).sum();

        BigDecimal[] rates = computeWeightedRates(catLogs, onProbation);
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
                .normMultiplier(normMultiplier)
                .normMultiplierDefault(normMultiplierDefault)
                .performanceCoefficient(performanceCoefficient)
                .approvedPerformanceCoefficient(approvedPerformanceCoefficient)
                .sourceType(category.getType() != null ? category.getType() : "WORK")
                .build();
    }

    private DailyReportCategory buildPlCategoryEntry(List<WorkLog> catLogs, WorkCodeCategory category,
                                                      DailyReport report, int reducedMinutes,
                                                     boolean onProbation, BigDecimal normMultiplier,
                                                     BigDecimal normMultiplierDefault) {
        int totalQuantity = catLogs.stream().mapToInt(wl -> safeInt(wl.getQuantity())).sum();
        int totalScrap = catLogs.stream().mapToInt(wl -> safeInt(wl.getScrap())).sum();
        int originalMinutes = catLogs.stream().mapToInt(wl -> safeInt(wl.getDurationMin())).sum();

        BigDecimal[] rates = computeWeightedRates(catLogs, onProbation);
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
                .normMultiplier(normMultiplier)
                .normMultiplierDefault(normMultiplierDefault)
                .performanceCoefficient(performanceCoefficient)
                .approvedPerformanceCoefficient(approvedPerformanceCoefficient)
                .sourceType(category.getType() != null ? category.getType() : "WORK")
                .build();
    }

    private DailyReportCategory buildPlbCategoryEntry(WorkCodeCategory plbCategory, DailyReport report,
                                                      int plbMinutes, BigDecimal normMultiplier,
                                                      BigDecimal normMultiplierDefault) {
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
                .normMultiplier(normMultiplier)
                .normMultiplierDefault(normMultiplierDefault)
                .performanceCoefficient(coefficient)
                .approvedPerformanceCoefficient(coefficient)
                .sourceType(plbCategory.getType() != null ? plbCategory.getType() : "WORK")
                .build();
    }

    /**
     * @param onProbation resolved ONCE for the shift by the caller. Work done on
     *   probation is credited at 100 %, and the paid rate comes from
     *   {@link WorkLogPerformanceCalculator#calculateApprovedPerformanceRate}
     *   rather than being recomputed here.
     *
     *   <p>This method used to reimplement {@code rate.min(ceiling)} inline, which
     *   made the payroll and the analytics two copies of one rule. They had not
     *   diverged yet only because nothing had changed the rule since.
     */
    private BigDecimal[] computeWeightedRates(List<WorkLog> logs, boolean onProbation) {
        BigDecimal weightedRate = BigDecimal.ZERO;
        BigDecimal weightedApprovedRate = BigDecimal.ZERO;
        for (WorkLog wl : logs) {
            int duration = safeInt(wl.getDurationMin());
            if (duration <= 0) continue;
            // The MEASURED rate is unchanged by probation and is what the log,
            // the report and the payslip all still show as the real figure.
            BigDecimal perfRate = performanceCalculator.calculatePerformanceRate(wl);
            BigDecimal approvedRate =
                    performanceCalculator.calculateApprovedPerformanceRate(wl, onProbation);
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
                                  WorkIntervalCalculator.VerifiedTime verified) {
        // Sum of the per-category minutes. This is NOT the shift duration — overlapping
        // wall-clock time appears in it once per category — but it remains the weighting
        // denominator for the performance coefficients, whose category weights must
        // still add up to 1. Changing that denominator would silently move payroll
        // figures, which is out of scope here.
        // WORKED minutes only. An absence row is time nobody worked, with a
        // coefficient of zero — left in the denominator it would drag the day's
        // efficiency down in proportion to how long somebody was away, which
        // measures their absence rather than their work.
        int categoryMinutesTotal = categories.stream()
                .filter(c -> !isAbsenceRow(c))
                .mapToInt(c -> safeInt(c.getTotalMinutes()))
                .sum();

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
        report.setTotalSickLeavePaidMinutes(totalSickLeavePaidMinutes);
        report.setTotalSickLeaveUnpaidMinutes(totalSickLeaveUnpaidMinutes);
        /*
         * The sums above already hold the absence: buildAbsenceCategories put a
         * row on the report for it, of the category's own type, so the ordinary
         * ABSENCE and SICK_LEAVE arms pick it up exactly as they would any other.
         * Only the compensation has no category to live on.
         */
        report.setTotalAbsencePaidMinutes(totalAbsencePaidMinutes);
        report.setTotalAbsenceUnpaidMinutes(totalAbsenceUnpaidMinutes);
        report.setTotalCompensatedMinutes(compensatedMinutesOf(workShift.getId()));
        int approvedMinutes = totalWeightedNormMinutes.setScale(0, RoundingMode.HALF_UP).intValue();
        report.setTotalApprovedMinutes(approvedMinutes);

        // Verified time is a separate quantity from the efficiency-weighted approved
        // minutes above: it weights each covered interval by the PL/PLB coefficient
        // from work_code_categories.norm_multiplier. The two are deliberately kept
        // apart so no coefficient is ever applied twice to the same minute.
        report.setTotalVerifiedMinutes(verified.verifiedMinutes());
        report.setTotalPlMinutes((int) verified.plMinutes());
        report.setTotalPlbMinutes((int) verified.plbMinutes());

        // WHICH MINUTES COUNT IS THE CATEGORY'S ANSWER, not this method's.
        // It was type = 'WORK' here, which meant the rule could only be changed
        // by changing code. affects_weekend_bonus was backfilled from exactly
        // that condition, so nothing moved when it took over.
        int bonusEligibleMinutes = 0;
        for (DailyReportCategory cat : categories) {
            if (Boolean.TRUE.equals(cat.getWorkCodeCategory().getAffectsWeekendBonus())) {
                // The row's own coefficient — the same number the payroll prices
                // this row at. Read from the row rather than re-derived from the
                // category, so a coefficient somebody typed moves the bonus with
                // the pay instead of leaving the two disagreeing.
                BigDecimal multiplier = coefficientKey(cat.getNormMultiplier());
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
            if (isAbsenceRow(category)) {
                continue;
            }
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

    /**
     * One category row per KIND of absence on the shift.
     *
     * <p>Built from the absence records rather than from logs, because no work
     * log can carry an absence — {@code work_logs.operation_id} is NOT NULL and
     * an absence is not an operation on a product. Without these rows the path
     * to a payslip has no absence on it at all: two hours missed showed up as a
     * smaller total with no line saying why.
     *
     * <p>Grouped by category, so several stretches away on one day read as one
     * line of NO rather than three. No quantity, no scrap, no weighted minutes
     * and no coefficient: an absence is time, not output.
     *
     * <p><b>Only the UNCOVERED part is priced.</b> Three hours missed with two
     * bought back by the overtime bank is one hour of NO, because the other two
     * were already worked — paid for once, on the day they were worked. Putting
     * the whole three on the line would charge the employee for time they had
     * already made up.
     *
     * <p>A fully covered absence therefore has no row at all. That is what a
     * neradni dan IS: the day was bought back, and there is nothing left of it
     * for the payroll to say.
     */
    private List<DailyReportCategory> buildAbsenceCategories(Long workShiftId, DailyReport report) {
        Map<Long, List<AbsenceRecord>> byCategory = absenceRecordRepository.findActiveForShift(workShiftId)
                .stream()
                .collect(Collectors.groupingBy(a -> a.getWorkCodeCategory().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        List<DailyReportCategory> rows = new ArrayList<>();
        for (List<AbsenceRecord> group : byCategory.values()) {
            WorkCodeCategory category = group.get(0).getWorkCodeCategory();
            int minutes = group.stream()
                    .mapToInt(a -> Math.max(0,
                            safeInt(a.getAbsenceMinutes()) - safeInt(a.getCompensatedMinutes())))
                    .sum();
            if (minutes == 0) {
                // Covered whole. Nothing is owed and nothing is shown.
                continue;
            }
            int paid = group.stream().mapToInt(a -> safeInt(a.getPaidMinutes())).sum();

            rows.add(DailyReportCategory.builder()
                    .dailyReport(report)
                    .workCodeCategory(category)
                    .totalMinutes(minutes)
                    // What the absence is PAID for, which for NO is nothing.
                    // Being compensated by the bank never changes this: the
                    // overtime buys the day's standing, not its wage.
                    .totalPaidMinutes(paid)
                    .totalQuantity(0)
                    .totalScrap(0)
                    .totalWeightedNormMinutes(BigDecimal.ZERO)
                    .normMultiplier(group.get(0).getNormMultiplierSnapshot())
                    .normMultiplierDefault(resolveMultiplierByCategory(category))
                    .performanceCoefficient(BigDecimal.ZERO)
                    .approvedPerformanceCoefficient(BigDecimal.ZERO)
                    .sourceType(category.getType() != null ? category.getType() : "ABSENCE")
                    .build());
        }
        return rows;
    }

    /**
     * TRUE for a row that stands for time nobody worked.
     *
     * <p>Kept out of both efficiency denominators. Left in, the day's rate would
     * fall in proportion to how long somebody was away — which measures their
     * absence rather than their work, and is not what a performance figure is.
     */
    private boolean isAbsenceRow(DailyReportCategory category) {
        return isType(category.getSourceType(), "ABSENCE")
                || isType(category.getSourceType(), "SICK_LEAVE");
    }

    /**
     * How much of the day's absence the overtime bank reached.
     *
     * <p>The one thing no category row can say: it is a property of the absence
     * record, written by the allocation, and two absences of the same category
     * may be covered differently.
     */
    private int compensatedMinutesOf(Long workShiftId) {
        return absenceRecordRepository.findActiveForShift(workShiftId).stream()
                .mapToInt(a -> safeInt(a.getCompensatedMinutes()))
                .sum();
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
