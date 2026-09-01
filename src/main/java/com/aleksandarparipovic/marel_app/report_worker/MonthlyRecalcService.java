package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceCompensationAllocator;
import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategory;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategory;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.notification.ReportNotificationService;
import com.aleksandarparipovic.marel_app.recalc_queue.MonthlyRecalcQueue;
import com.aleksandarparipovic.marel_app.recalc_queue.MonthlyRecalcQueueRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyRecalcService {

    private final AppSettingService appSettingService;
    private final MonthlyRecalcQueueRepository queueRepo;
    private final MonthlyReportRepository reportRepo;
    private final MonthlyReportCategoryRepository categoryRepo;
    private final DailyReportRepository dailyReportRepo;
    private final DailyReportCategoryRepository dailyCategoryRepo;
    private final ReportNotificationService notificationService;
    private final RecalcWorkerProperties properties;
    private final MeterRegistry meterRegistry;
    private final EmployeeRecordService employeeRecordService;
    private final TransactionTemplate transactionTemplate;
    private final AbsenceCompensationAllocator absenceCompensationAllocator;

    public void processJob(Long jobId) {
        long startedAt = System.nanoTime();
        MonthlyRecalcQueue snapshot = queueRepo.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Monthly recalc job not found: " + jobId));

        if (!"IN_PROGRESS".equals(snapshot.getStatus())) {
            return;
        }

        int claimedVersion = snapshot.getVersion() == null ? 0 : snapshot.getVersion();
        Long employeeId = snapshot.getEmployee().getId();
        int year = snapshot.getReportYear();
        int month = snapshot.getReportMonth();

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        /*
         * WHICH OVERTIME PAID FOR WHICH ABSENCE, decided before the month is added up.
         *
         * The whole month is planned again from the current bank and the current
         * absences, so a day refused earlier is bought the moment the bank grows —
         * a supervisor adding two hours to an earlier shift turns a six-hour bank
         * into eight, and the no-show that stayed NO becomes ND here.
         *
         * A day whose outcome moved has an ND log written or removed, and the
         * allocator requeues that day. This pass then aggregates daily reports
         * that are about to be rebuilt, and the requeued day brings the month back
         * to be summed again — the same eventual-consistency the weekend-bonus
         * recheck already relies on. When nothing moved, nothing is requeued.
         *
         * Its own transaction, outside the write phase below, so a configuration
         * error (no ND operation) fails this job through the queue's retry and
         * last_error path rather than half-writing a monthly report.
         */
        absenceCompensationAllocator.allocate(employeeId, YearMonth.of(year, month));

        // Heavy reads stay outside the short write transaction.
        List<DailyReport> dailyReports = dailyReportRepo.findByEmployee_IdAndWorkDateBetween(employeeId, start, end);
        List<Long> dailyReportIds = dailyReports.stream().map(DailyReport::getId).toList();
        List<DailyReportCategory> dailyCategories = dailyReportIds.isEmpty()
                ? List.of()
                : dailyCategoryRepo.findAllByDailyReportIds(dailyReportIds);

        Boolean processed = transactionTemplate.execute(status -> processJobWritePhase(
                jobId,
                claimedVersion,
                employeeId,
                year,
                month,
                start,
                end,
                dailyReports,
                dailyCategories,
                startedAt
        ));

        if (!Boolean.TRUE.equals(processed)) {
            return;
        }
    }

    private boolean processJobWritePhase(Long jobId,
                                         int claimedVersion,
                                         Long employeeId,
                                         int year,
                                         int month,
                                         LocalDate start,
                                         LocalDate end,
                                         List<DailyReport> dailyReports,
                                         List<DailyReportCategory> dailyCategories,
                                         long startedAt) {
        EmployeeRecord employeeRecord = employeeRecordService.getOrCreateMonthlyRecord(employeeId, start);

        MonthlyReport report = reportRepo.findByEmployeeRecord_Id(employeeRecord.getId())
                .orElseGet(() -> MonthlyReport.builder()
                        .employeeRecord(employeeRecord)
                        .startDate(start)
                        .endDate(end)
                        .version(0)
                        .calcVersion(0)
                        .totalWorkMinutes(0)
                        .totalApprovedMinutes(0)
                        .totalShiftMinutes(0)
                        .totalQuantity(0)
                        .totalScrap(0)
                        .totalAbsencePaidMinutes(0)
                        .totalAbsenceUnpaidMinutes(0)
                        .totalAbsenceMinutes(0)
                        .totalSickLeavePaidMinutes(0)
                        .totalSickLeaveUnpaidMinutes(0)
                        .totalSickLeaveMinutes(0)
                        .totalWeightedNormMinutes(BigDecimal.ZERO)
                        .build());

        if (report.getId() == null) {
            // Persist once to guarantee stable FK target for full category rebuilds.
            report = reportRepo.saveAndFlush(report);
        }

        MonthlyRecalcQueue locked = queueRepo.findByIdForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException("Monthly recalc job not found after claim: " + jobId));

        int latestVersion = locked.getVersion() == null ? 0 : locked.getVersion();
        if (!"IN_PROGRESS".equals(locked.getStatus()) || latestVersion != claimedVersion) {
            locked.setStatus("PENDING");
            locked.setClaimedAt(null);
            locked.setClaimedBy(null);
            locked.setRequestedAt(OffsetDateTime.now());
            queueRepo.save(locked);
            log.debug("Monthly job {} rescheduled due to newer version {} > {}", jobId, latestVersion, claimedVersion);
            meterRegistry.counter("recalc.jobs.rescheduled", "type", "monthly").increment();
            return false;
        }

        if (report.getId() != null) {
            categoryRepo.deleteAllByMonthlyReportId(report.getId());
        }

        List<MonthlyReportCategory> monthlyCategories = buildMonthlyCategories(dailyCategories, report);
        if (!monthlyCategories.isEmpty()) {
            categoryRepo.saveAll(monthlyCategories);
        }

        fillMonthlyTotals(report, dailyReports, monthlyCategories, start);
        MonthlyReport savedReport = reportRepo.saveAndFlush(report);
        categoryRepo.flush();

        locked.setStatus("DONE");
        locked.setProcessedAt(OffsetDateTime.now());
        locked.setClaimedAt(null);
        locked.setClaimedBy(null);
        queueRepo.save(locked);

        if (locked.getRequestedAt() != null) {
            meterRegistry.timer("recalc.queue.latency", "type", "monthly")
                    .record(Duration.between(locked.getRequestedAt(), locked.getProcessedAt()));
        }

        log.info("Monthly report recalculated for employee={} {}/{} version={}",
                employeeId, year, month, savedReport.getVersion());

        notificationService.sendMonthlyReportUpdate(employeeRecord.getId());
        meterRegistry.counter("recalc.jobs.processed", "type", "monthly").increment();
        meterRegistry.timer("recalc.job.duration", "type", "monthly")
                .record(Duration.ofNanos(System.nanoTime() - startedAt));

        return true;
    }

    @Transactional
    public void markFailed(Long jobId, String errorMessage) {
        queueRepo.findById(jobId).ifPresent(job -> {
            int retries = job.getRetryCount() != null ? job.getRetryCount() : 0;
            int next = retries + 1;
            job.setRetryCount(next);
            job.setLastError(errorMessage);
            job.setClaimedAt(null);
            job.setClaimedBy(null);

            if (next >= properties.getMaxRetry()) {
                job.setStatus("FAILED");
                job.setProcessedAt(OffsetDateTime.now());
                meterRegistry.counter("recalc.jobs.failed", "type", "monthly").increment();
            } else {
                long backoffMs = computeBackoffMs(next);
                job.setStatus("PENDING");
                job.setRequestedAt(OffsetDateTime.now().plusNanos(backoffMs * 1_000_000));
                meterRegistry.counter("recalc.jobs.retry", "type", "monthly").increment();
            }
            queueRepo.save(job);
        });
    }

    private long computeBackoffMs(int retryCount) {
        long baseMs = Math.max(1L, properties.getBaseBackoffMs());
        return Math.min(300_000L, baseMs * (1L << Math.min(retryCount, 10)));
    }

    private List<MonthlyReportCategory> buildMonthlyCategories(List<DailyReportCategory> dailyCategories, MonthlyReport report) {
        if (dailyCategories.isEmpty()) {
            return List.of();
        }

        // Grouped by category AND coefficient, exactly as the daily rows are: a
        // month that saw the same category at two coefficients keeps them apart
        // all the way to the payroll, which is where they are priced.
        Map<CategoryRowKey, List<DailyReportCategory>> byCategory = dailyCategories.stream()
                .collect(Collectors.groupingBy(
                        dc -> new CategoryRowKey(
                                dc.getWorkCodeCategory().getId(), coefficientKey(dc.getNormMultiplier())),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return byCategory.entrySet().stream().map(bucket -> {
            List<DailyReportCategory> rows = bucket.getValue();
            WorkCodeCategory category = rows.getFirst().getWorkCodeCategory();
            int totalMinutes = rows.stream().mapToInt(c -> safeInt(c.getTotalMinutes())).sum();
            int totalPaidMinutes = rows.stream().mapToInt(c -> safeInt(c.getTotalPaidMinutes())).sum();
            int totalQuantity = rows.stream().mapToInt(c -> safeInt(c.getTotalQuantity())).sum();
            int totalScrap = rows.stream().mapToInt(c -> safeInt(c.getTotalScrap())).sum();
            BigDecimal totalWeightedNormMinutes = rows.stream()
                    .map(DailyReportCategory::getTotalWeightedNormMinutes)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(4, RoundingMode.HALF_UP);

            return MonthlyReportCategory.builder()
                    .monthlyReport(report)
                    .workCodeCategory(category)
                    .totalMinutes(totalMinutes)
                    .totalPaidMinutes(totalPaidMinutes)
                    .totalQuantity(totalQuantity)
                    .totalScrap(totalScrap)
                    .totalWeightedNormMinutes(totalWeightedNormMinutes)
                    .normMultiplier(bucket.getKey().coefficient())
                    // Every daily row in this bucket shares the category, so they
                    // share what the category resolves to; the first is as good as
                    // any and cheaper than proving they agree.
                    .normMultiplierDefault(coefficientKey(rows.getFirst().getNormMultiplierDefault()))
                    .totalApprovedMinutes(null)
                    .sourceType(category.getType() != null ? category.getType() : "WORK")
                    .build();
        }).toList();
    }

    /** A row standing for time nobody worked; see the two sums that skip it. */
    private static boolean isAbsenceCategory(MonthlyReportCategory category) {
        String type = category.getSourceType();
        return "ABSENCE".equalsIgnoreCase(type) || "SICK_LEAVE".equalsIgnoreCase(type);
    }

    private void fillMonthlyTotals(MonthlyReport report,
                                   List<DailyReport> dailyReports,
                                   List<MonthlyReportCategory> monthlyCategories,
                                   LocalDate periodStart) {
        int totalShiftMinutes = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalShiftMinutes())).sum();
        int totalWorkMinutes = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalWorkMinutes())).sum();
        int totalAbsencePaidMinutes = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalAbsencePaidMinutes())).sum();
        int totalAbsenceUnpaidMinutes = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalAbsenceUnpaidMinutes())).sum();
        int totalAbsenceMinutes = totalAbsencePaidMinutes + totalAbsenceUnpaidMinutes;
        int totalSickLeavePaidMinutes = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalSickLeavePaidMinutes())).sum();
        int totalSickLeaveUnpaidMinutes = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalSickLeaveUnpaidMinutes())).sum();
        int totalSickLeaveMinutes = totalSickLeavePaidMinutes + totalSickLeaveUnpaidMinutes;
        /*
         * THE MONTH'S OWN BONUS MEASURE, from the categories rather than from the
         * work total.
         *
         * Raw minutes, not weighted: the monthly bonus asks how many hours
         * somebody put in, which is what total_work_minutes answered before this.
         * The weekend bonus weights its minutes by the coefficient because it
         * asks a different question of a single day.
         *
         * The manual corrections are NOT added here. They belong to the payroll
         * item, not to the report, and MonthlyBonusCalculator adds them where it
         * can see both.
         */
        int monthlyBonusEligibleMinutes = monthlyCategories.stream()
                .filter(mc -> Boolean.TRUE.equals(mc.getWorkCodeCategory().getAffectsMonthlyBonus()))
                .mapToInt(mc -> safeInt(mc.getTotalMinutes()))
                .sum();

        int totalQuantity = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalQuantity())).sum();
        int totalScrap = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalScrap())).sum();
        int mealAllowanceNum = dailyReports.stream().mapToInt(dr -> safeInt(dr.getMealsCount())).sum();

        // WORKED rows only, for the reason fillDailyTotals gives: an absence row
        // carries its minutes so the payslip can show them, and performance is
        // measured over work. performanceCoefficient below divides this by
        // total_shift_minutes, which never contained the absence — counting it in
        // the numerator alone would push a month above 100 % for being away.
        BigDecimal totalWeightedNormMinutes = monthlyCategories.stream()
                .filter(mc -> !isAbsenceCategory(mc))
                .map(MonthlyReportCategory::getTotalWeightedNormMinutes)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        // The coefficient the row was BUILT at, not the one the category carries
        // today. Recalculating a closed month must reproduce it, and a row whose
        // coefficient somebody typed has no other place to read it from.
        BigDecimal totalApprovedMinutes = monthlyCategories.stream()
                .filter(mc -> !isAbsenceCategory(mc))
                .map(mc -> mc.getTotalWeightedNormMinutes().multiply(coefficientKey(mc.getNormMultiplier())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal performanceCoefficient = totalShiftMinutes > 0
                ? totalWeightedNormMinutes.divide(BigDecimal.valueOf(totalShiftMinutes), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        report.setTotalShiftMinutes(totalShiftMinutes);
        report.setTotalWorkMinutes(totalWorkMinutes);
        report.setMonthlyBonusEligibleMinutes(monthlyBonusEligibleMinutes);
        report.setTotalAbsencePaidMinutes(totalAbsencePaidMinutes);
        report.setTotalAbsenceUnpaidMinutes(totalAbsenceUnpaidMinutes);
        report.setTotalAbsenceMinutes(totalAbsenceMinutes);
        report.setTotalSickLeavePaidMinutes(totalSickLeavePaidMinutes);
        report.setTotalSickLeaveUnpaidMinutes(totalSickLeaveUnpaidMinutes);
        report.setTotalSickLeaveMinutes(totalSickLeaveMinutes);
        report.setTotalApprovedMinutes(totalApprovedMinutes.setScale(0, RoundingMode.HALF_UP).intValue());
        report.setTotalQuantity(totalQuantity);
        report.setTotalScrap(totalScrap);
        report.setTotalWeightedNormMinutes(totalWeightedNormMinutes);
        report.setMealAllowanceNum(mealAllowanceNum);
        // THE MONTHLY FIGURE WAS NEVER CAPPED. approved_performance_rate was set to
        // exactly performance_rate, so "approved" meant nothing here — while
        // DailySummaryService has capped the daily figure at max_efficiency_percent
        // all along. A month could therefore show an approved efficiency the daily
        // reports it is built from could not.
        //
        // Resolved at the LAST day of the period, not the first: the month's
        // efficiency is the whole month's, so the ceiling in force when it ended is
        // the one it was measured against. Reading it at now() would let a change
        // made in March silently lift February's approved figure the next time
        // February is recalculated.
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        BigDecimal maxEfficiencyPercent = appSettingService.getMaxEfficiencyPercentOn(periodEnd);

        BigDecimal performanceRate = performanceCoefficient
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal approvedRate = performanceRate.min(maxEfficiencyPercent);

        report.setPerformanceCoefficient(performanceCoefficient);
        // Kept in step with the rate. Leaving the coefficient uncapped while the
        // rate is capped would make the two disagree about the same quantity.
        report.setApprovedPerformanceCoefficient(
                approvedRate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        report.setPerformanceRate(performanceRate);
        report.setApprovedPerformanceRate(approvedRate);
        report.setCalcVersion((report.getCalcVersion() != null ? report.getCalcVersion() : 0) + 1);
        report.setLastRecalculatedAt(OffsetDateTime.now());
        if (report.getStatus() == null) {
            report.setStatus("OPEN");
        }
    }

    /** One month row per category AT ONE COEFFICIENT — see DailyRecalcService. */
    private record CategoryRowKey(Long categoryId, BigDecimal coefficient) {}

    /** Rows group by value, not by how the value happens to be written. */
    private static BigDecimal coefficientKey(BigDecimal coefficient) {
        return (coefficient == null ? BigDecimal.ONE : coefficient)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
