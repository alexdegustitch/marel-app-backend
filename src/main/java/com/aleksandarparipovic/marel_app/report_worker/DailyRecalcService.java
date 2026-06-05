package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategory;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.notification.ReportNotificationService;
import com.aleksandarparipovic.marel_app.recalc_queue.DailyRecalcQueue;
import com.aleksandarparipovic.marel_app.recalc_queue.DailyRecalcQueueRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyRecalcService {

    private static final int MAX_RETRY = 5;

    private final DailyRecalcQueueRepository queueRepo;
    private final DailyReportRepository reportRepo;
    private final DailyReportCategoryRepository categoryRepo;
    private final WorkLogRepository workLogRepo;
    private final RecalcQueueService recalcQueueService;
    private final ReportNotificationService notificationService;

    @Transactional
    public void processJob(Long jobId) {
        // ── 1. Lock job with pessimistic write lock (prevents concurrent workers) ──────
        DailyRecalcQueue job = queueRepo.findByIdForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException("Daily recalc job not found: " + jobId));

        // ── Double-execution protection ───────────────────────────────────────────────
        if ("PROCESSED".equals(job.getStatus())) {
            log.debug("Daily recalc job {} already PROCESSED, skipping", jobId);
            return;
        }
        if ("FAILED".equals(job.getStatus())) {
            log.debug("Daily recalc job {} already FAILED, skipping", jobId);
            return;
        }

        // ── 2. Mark as PROCESSING (atomic update) ─────────────────────────────────────
        job.setStatus("PROCESSING");
        queueRepo.save(job);

        WorkShift workShift = job.getWorkShift();
        Employee employee  = workShift.getEmployee();
        LocalDate workDate = workShift.getWorkDate();

        // ── 3. Load active work logs (with work-code eagerly fetched) ─────────────────
        List<WorkLog> logs = workLogRepo.findActiveLogsWithCodeForShift(workShift.getId());

        // ── 4. Aggregate totals in SINGLE LOOP (performance) ──────────────────────────
        AggregateData agg = aggregateLogsInSinglePass(logs);

        // ── 5. Upsert DailyReport ────────────────────────────────────────────────────
        DailyReport report = reportRepo.findByWorkShiftId(workShift.getId())
                .orElseGet(() -> DailyReport.builder()
                        .employee(employee)
                        .workDate(workDate)
                        .workShift(workShift)
                        .calcVersion(0)
                        .build());

        report.setEmployee(employee);
        report.setWorkDate(workDate);
        report.setWorkShift(workShift);
        report.setTotalShiftMinutes(workShift.getTotalMinutes() != null ? workShift.getTotalMinutes() : 0);
        report.setTotalWorkMinutes(agg.totalWorkMinutes);
        report.setTotalApprovedMinutes(agg.totalWorkMinutes);
        report.setTotalQuantity(agg.totalQuantity);
        report.setTotalScrap(agg.totalScrap);
        report.setTotalWeightedNormMinutes(agg.totalWeightedNorm);
        // NOTE: absence / compensated minutes require separate absence data; set to 0 for now
        report.setTotalAbsenceMinutes(0);
        report.setTotalPaidAbsenceMinutes(0);
        report.setTotalUnpaidAbsenceMinutes(0);
        report.setTotalCompensatedMinutes(0);
        report.setCalcVersion((report.getCalcVersion() != null ? report.getCalcVersion() : 0) + 1);
        report.setLastRecalculatedAt(OffsetDateTime.now());

        DailyReport savedReport = reportRepo.save(report);
        // NOTE: @Version on DailyReport is auto-incremented by Hibernate on save()
        //       This version is used by payroll as an indicator of freshness.

        // ── 6. Bulk DELETE then rebuild DailyReportCategories ──────────────────────────
        categoryRepo.deleteAllByDailyReportId(savedReport.getId());

        Map<Long, List<WorkLog>> byCategory = logs.stream()
                .filter(wl -> wl.getWorkCode() != null)
                .collect(Collectors.groupingBy(wl -> wl.getWorkCode().getId()));

        List<DailyReportCategory> categories = byCategory.entrySet().stream().map(entry -> {
            List<WorkLog>     catLogs = entry.getValue();
            WorkCodeCategory  wcc     = catLogs.getFirst().getWorkCode();
            double            mult    = wcc.getNormMultiplier() != null ? wcc.getNormMultiplier() : 1.0;
            BigDecimal        coeff   = BigDecimal.valueOf(mult);

            int catMinutes = 0, catQty = 0, catScrap = 0;
            BigDecimal wn = BigDecimal.ZERO;

            for (WorkLog wl : catLogs) {
                catMinutes += wl.getDurationMin() != null ? wl.getDurationMin() : 0;
                catQty += wl.getQuantity() != null ? wl.getQuantity() : 0;
                catScrap += wl.getScrap() != null ? wl.getScrap() : 0;
                int dur = wl.getDurationMin() != null ? wl.getDurationMin() : 0;
                wn = wn.add(BigDecimal.valueOf(dur).multiply(coeff));
            }

            BigDecimal perfRate = catMinutes > 0
                    ? wn.divide(BigDecimal.valueOf(catMinutes), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            return DailyReportCategory.builder()
                    .dailyReport(savedReport)
                    .workCodeCategory(wcc)
                    .totalMinutes(catMinutes)
                    .totalCompensatedMinutes(0)
                    .totalApprovedMinutes(catMinutes)
                    .totalQuantity(catQty)
                    .totalScrap(catScrap)
                    .totalWeightedNormMinutes(wn)
                    .performanceRate(perfRate)
                    .approvedPerformanceRate(perfRate)
                    .categoryCoefficientSnapshot(coeff)
                    .sourceType(wcc.getType() != null ? wcc.getType() : "WORK")
                    .build();
        }).toList();

        categoryRepo.saveAll(categories);

        // ── 7. Enqueue monthly recalculation ─────────────────────────────────────────
        recalcQueueService.enqueueMonthlyJob(employee, workDate.getYear(), workDate.getMonthValue(), "DAILY_RECALC");

        // ── 8. Mark job PROCESSED ───────────────────────────────────────────────────
        job.setStatus("PROCESSED");
        job.setProcessedAt(OffsetDateTime.now());
        queueRepo.save(job);

        log.info("Daily report recalculated for employee={} shift={} date={} version={}",
                employee.getId(), workShift.getId(), workDate, report.getVersion());

        // ── 9. Emit WebSocket notification ───────────────────────────────────────────
        notificationService.sendDailyReportUpdate(employee.getId(), workDate, workShift.getId());
    }

    @Transactional
    public void markFailed(Long jobId, String errorMessage) {
        queueRepo.findById(jobId).ifPresent(job -> {
            int retries = job.getRetryCount() != null ? job.getRetryCount() : 0;
            job.setRetryCount(retries + 1);
            job.setErrorMessage(errorMessage);
            job.setStatus((retries + 1) >= MAX_RETRY ? "FAILED" : "PENDING");
            if (!"FAILED".equals(job.getStatus())) {
                job.setLockedAt(null);
                job.setLockedBy(null);
            }
            queueRepo.save(job);
        });
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────────

    /** Single-pass aggregation to avoid multiple stream iterations. */
    private AggregateData aggregateLogsInSinglePass(List<WorkLog> logs) {
        int totalWorkMinutes = 0;
        int totalQuantity = 0;
        int totalScrap = 0;
        BigDecimal totalWeightedNorm = BigDecimal.ZERO;

        for (WorkLog wl : logs) {
            totalWorkMinutes += wl.getDurationMin() != null ? wl.getDurationMin() : 0;
            totalQuantity += wl.getQuantity() != null ? wl.getQuantity() : 0;
            totalScrap += wl.getScrap() != null ? wl.getScrap() : 0;

            double mult = wl.getWorkCode() != null && wl.getWorkCode().getNormMultiplier() != null
                    ? wl.getWorkCode().getNormMultiplier() : 1.0;
            int dur = wl.getDurationMin() != null ? wl.getDurationMin() : 0;
            totalWeightedNorm = totalWeightedNorm.add(BigDecimal.valueOf(dur * mult));
        }

        return new AggregateData(totalWorkMinutes, totalQuantity, totalScrap, totalWeightedNorm);
    }

    private static class AggregateData {
        int totalWorkMinutes;
        int totalQuantity;
        int totalScrap;
        BigDecimal totalWeightedNorm;

        AggregateData(int totalWorkMinutes, int totalQuantity, int totalScrap, BigDecimal totalWeightedNorm) {
            this.totalWorkMinutes = totalWorkMinutes;
            this.totalQuantity = totalQuantity;
            this.totalScrap = totalScrap;
            this.totalWeightedNorm = totalWeightedNorm;
        }
    }
}

