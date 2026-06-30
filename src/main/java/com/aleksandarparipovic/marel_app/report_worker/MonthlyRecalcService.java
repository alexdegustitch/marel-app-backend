package com.aleksandarparipovic.marel_app.report_worker;

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
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyRecalcService {

    private final MonthlyRecalcQueueRepository queueRepo;
    private final MonthlyReportRepository reportRepo;
    private final MonthlyReportCategoryRepository categoryRepo;
    private final DailyReportRepository dailyReportRepo;
    private final DailyReportCategoryRepository dailyCategoryRepo;
    private final ReportNotificationService notificationService;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final RecalcWorkerProperties properties;
    private final MeterRegistry meterRegistry;
    private final EmployeeRecordService employeeRecordService;
    private final TransactionTemplate transactionTemplate;

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

        Map<Long, List<DailyReportCategory>> byCategory = dailyCategories.stream()
                .collect(Collectors.groupingBy(dc -> dc.getWorkCodeCategory().getId()));

        return byCategory.values().stream().map(rows -> {
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
                    .totalApprovedMinutes(null)
                    .sourceType(category.getType() != null ? category.getType() : "WORK")
                    .build();
        }).toList();
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
        int totalQuantity = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalQuantity())).sum();
        int totalScrap = dailyReports.stream().mapToInt(dr -> safeInt(dr.getTotalScrap())).sum();
        int mealAllowanceNum = dailyReports.stream().mapToInt(dr -> safeInt(dr.getMealsCount())).sum();

        BigDecimal totalWeightedNormMinutes = monthlyCategories.stream()
                .map(MonthlyReportCategory::getTotalWeightedNormMinutes)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal totalApprovedMinutes = monthlyCategories.stream()
                .map(mc -> mc.getTotalWeightedNormMinutes().multiply(resolveMultiplier(mc.getWorkCodeCategory(), periodStart)))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal performanceCoefficient = totalShiftMinutes > 0
                ? totalWeightedNormMinutes.divide(BigDecimal.valueOf(totalShiftMinutes), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        report.setTotalShiftMinutes(totalShiftMinutes);
        report.setTotalWorkMinutes(totalWorkMinutes);
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
        report.setPerformanceCoefficient(performanceCoefficient);
        report.setApprovedPerformanceCoefficient(performanceCoefficient);
        report.setPerformanceRate(performanceCoefficient.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));
        report.setApprovedPerformanceRate(performanceCoefficient.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));
        report.setCalcVersion((report.getCalcVersion() != null ? report.getCalcVersion() : 0) + 1);
        report.setLastRecalculatedAt(OffsetDateTime.now());
        if (report.getStatus() == null) {
            report.setStatus("OPEN");
        }
    }

    private BigDecimal resolveMultiplier(WorkCodeCategory category, LocalDate atDate) {
        if (category == null || category.getCategoryNo() == null) {
            return BigDecimal.ONE;
        }
        return workCodeCategoryRepository.findEffectiveNormMultiplier(category.getCategoryNo(), atDate)
                .orElse(BigDecimal.valueOf(category.getNormMultiplier() == null ? 1d : category.getNormMultiplier()));
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
