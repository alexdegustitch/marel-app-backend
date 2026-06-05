package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategory;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategory;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.notification.ReportNotificationService;
import com.aleksandarparipovic.marel_app.recalc_queue.MonthlyRecalcQueue;
import com.aleksandarparipovic.marel_app.recalc_queue.MonthlyRecalcQueueRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonthlyRecalcService {

    private static final int MAX_RETRY = 5;

    private final MonthlyRecalcQueueRepository queueRepo;
    private final MonthlyReportRepository reportRepo;
    private final MonthlyReportCategoryRepository categoryRepo;
    private final DailyReportRepository dailyReportRepo;
    private final DailyReportCategoryRepository dailyCategoryRepo;
    private final ReportNotificationService notificationService;

    @Transactional
    public void processJob(Long jobId) {
        // ── 1. Lock job with pessimistic write lock (prevents concurrent workers) ──────
        MonthlyRecalcQueue job = queueRepo.findByIdForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException("Monthly recalc job not found: " + jobId));

        // ── Double-execution protection ───────────────────────────────────────────────
        if ("PROCESSED".equals(job.getStatus())) {
            log.debug("Monthly recalc job {} already PROCESSED, skipping", jobId);
            return;
        }
        if ("FAILED".equals(job.getStatus())) {
            log.debug("Monthly recalc job {} already FAILED, skipping", jobId);
            return;
        }

        // ── 2. Mark as PROCESSING (atomic update) ─────────────────────────────────────
        job.setStatus("PROCESSING");
        queueRepo.save(job);

        Employee employee = job.getEmployee();
        int year  = job.getReportYear();
        int month = job.getReportMonth();

        // ── 3. Load all daily reports for employee/month ──────────────────────────────
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());
        List<DailyReport> dailyReports =
                dailyReportRepo.findByEmployee_IdAndWorkDateBetween(employee.getId(), start, end);

        // ── 4. Aggregate from daily reports (single pass) ─────────────────────────────
        AggregateData agg = aggregateDailyReportsInSinglePass(dailyReports);

        // ── 5. Upsert MonthlyReport ──────────────────────────────────────────────────
        MonthlyReport report = reportRepo.findByEmployee_IdAndReportYearAndReportMonth(
                        employee.getId(), year, month)
                .orElseGet(() -> MonthlyReport.builder()
                        .employee(employee)
                        .reportYear(year)
                        .reportMonth(month)
                        .version(0)
                        .build());

        report.setTotalShiftMinutes(agg.totalShiftMinutes);
        report.setTotalWorkMinutes(agg.totalWorkMinutes);
        report.setTotalAbsenceMinutes(agg.totalAbsenceMinutes);
        report.setTotalPaidAbsenceMinutes(agg.totalPaidAbsenceMinutes);
        report.setTotalUnpaidAbsenceMinutes(agg.totalUnpaidAbsenceMinutes);
        report.setTotalCompensatedMinutes(agg.totalCompensatedMinutes);
        report.setTotalApprovedMinutes(agg.totalApprovedMinutes);
        report.setTotalQuantity(agg.totalQuantity);
        report.setTotalScrap(agg.totalScrap);
        report.setTotalEffectiveMinutes(agg.totalEffective);
        report.setMealAllowanceNum(agg.mealAllowanceNum);
        report.setCalcVersion((report.getCalcVersion() != null ? report.getCalcVersion() : 0) + 1);
        report.setLastRecalculatedAt(OffsetDateTime.now());
        if (report.getStatus() == null) report.setStatus("OPEN");

        MonthlyReport savedReport = reportRepo.save(report);
        // NOTE: @Version on MonthlyReport is auto-incremented by Hibernate on save()
        //       This version is used by payroll items as an indicator of staleness.

        // ── 6. Bulk DELETE then rebuild MonthlyReportCategories ──────────────────────
        categoryRepo.deleteAllByMonthlyReportId(savedReport.getId());

        List<Long> dailyReportIds = dailyReports.stream().map(DailyReport::getId).toList();
        if (!dailyReportIds.isEmpty()) {
            List<DailyReportCategory> dailyCats =
                    dailyCategoryRepo.findAllByDailyReportIds(dailyReportIds);

            Map<Long, List<DailyReportCategory>> byCat = dailyCats.stream()
                    .collect(Collectors.groupingBy(dc -> dc.getWorkCodeCategory().getId()));

            List<MonthlyReportCategory> monthlyCats = byCat.entrySet().stream().map(e -> {
                List<DailyReportCategory> dcs = e.getValue();
                WorkCodeCategory wcc  = dcs.getFirst().getWorkCodeCategory();

                int catMinutes = 0, catApproved = 0, catQty = 0, catScrap = 0;
                BigDecimal wn = BigDecimal.ZERO;

                for (DailyReportCategory dc : dcs) {
                    catMinutes += dc.getTotalMinutes() != null ? dc.getTotalMinutes() : 0;
                    catApproved += dc.getTotalApprovedMinutes() != null ? dc.getTotalApprovedMinutes() : 0;
                    catQty += dc.getTotalQuantity() != null ? dc.getTotalQuantity() : 0;
                    catScrap += dc.getTotalScrap() != null ? dc.getTotalScrap() : 0;
                    wn = wn.add(dc.getTotalWeightedNormMinutes() != null ? dc.getTotalWeightedNormMinutes() : BigDecimal.ZERO);
                }

                BigDecimal effHrs = BigDecimal.valueOf(catApproved)
                        .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);

                return MonthlyReportCategory.builder()
                        .monthlyReport(savedReport)
                        .workCodeCategory(wcc)
                        .totalMinutes(catMinutes)
                        .totalPaidMinutes(catApproved)
                        .totalQuantity(catQty)
                        .totalScrap(catScrap)
                        .weightedNormMinutes(wn)
                        .effectiveHours(effHrs)
                        .sourceType(wcc.getType() != null ? wcc.getType() : "WORK")
                        .build();
            }).toList();

            categoryRepo.saveAll(monthlyCats);
        }

        // ── 7. Mark job PROCESSED ───────────────────────────────────────────────────
        job.setStatus("PROCESSED");
        job.setProcessedAt(OffsetDateTime.now());
        queueRepo.save(job);

        log.info("Monthly report recalculated for employee={} {}/{} version={}",
                employee.getId(), year, month, report.getVersion());

        // ── 8. Emit WebSocket notification ───────────────────────────────────────────
        notificationService.sendMonthlyReportUpdate(employee.getId(), year, month);
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
    private AggregateData aggregateDailyReportsInSinglePass(List<DailyReport> reports) {
        int totalShiftMinutes = 0;
        int totalWorkMinutes = 0;
        int totalAbsenceMinutes = 0;
        int totalPaidAbsenceMinutes = 0;
        int totalUnpaidAbsenceMinutes = 0;
        int totalCompensatedMinutes = 0;
        int totalApprovedMinutes = 0;
        int totalQuantity = 0;
        int totalScrap = 0;
        int mealAllowanceNum = 0;
        BigDecimal totalEffective = BigDecimal.ZERO;

        for (DailyReport dr : reports) {
            totalShiftMinutes += dr.getTotalShiftMinutes() != null ? dr.getTotalShiftMinutes() : 0;
            totalWorkMinutes += dr.getTotalWorkMinutes() != null ? dr.getTotalWorkMinutes() : 0;
            totalAbsenceMinutes += dr.getTotalAbsenceMinutes() != null ? dr.getTotalAbsenceMinutes() : 0;
            totalPaidAbsenceMinutes += dr.getTotalPaidAbsenceMinutes() != null ? dr.getTotalPaidAbsenceMinutes() : 0;
            totalUnpaidAbsenceMinutes += dr.getTotalUnpaidAbsenceMinutes() != null ? dr.getTotalUnpaidAbsenceMinutes() : 0;
            totalCompensatedMinutes += dr.getTotalCompensatedMinutes() != null ? dr.getTotalCompensatedMinutes() : 0;
            totalApprovedMinutes += dr.getTotalApprovedMinutes() != null ? dr.getTotalApprovedMinutes() : 0;
            totalQuantity += dr.getTotalQuantity() != null ? dr.getTotalQuantity() : 0;
            totalScrap += dr.getTotalScrap() != null ? dr.getTotalScrap() : 0;
            mealAllowanceNum += dr.getMealAllowanceNum() != null ? dr.getMealAllowanceNum() : 0;
            totalEffective = totalEffective.add(dr.getTotalWeightedNormMinutes() != null ? dr.getTotalWeightedNormMinutes() : BigDecimal.ZERO);
        }

        return new AggregateData(
            totalShiftMinutes, totalWorkMinutes, totalAbsenceMinutes,
            totalPaidAbsenceMinutes, totalUnpaidAbsenceMinutes, totalCompensatedMinutes,
            totalApprovedMinutes, totalQuantity, totalScrap, mealAllowanceNum, totalEffective
        );
    }

    private static class AggregateData {
        int totalShiftMinutes;
        int totalWorkMinutes;
        int totalAbsenceMinutes;
        int totalPaidAbsenceMinutes;
        int totalUnpaidAbsenceMinutes;
        int totalCompensatedMinutes;
        int totalApprovedMinutes;
        int totalQuantity;
        int totalScrap;
        int mealAllowanceNum;
        BigDecimal totalEffective;

        AggregateData(int totalShiftMinutes, int totalWorkMinutes, int totalAbsenceMinutes,
                int totalPaidAbsenceMinutes, int totalUnpaidAbsenceMinutes, int totalCompensatedMinutes,
                int totalApprovedMinutes, int totalQuantity, int totalScrap, int mealAllowanceNum,
                BigDecimal totalEffective) {
            this.totalShiftMinutes = totalShiftMinutes;
            this.totalWorkMinutes = totalWorkMinutes;
            this.totalAbsenceMinutes = totalAbsenceMinutes;
            this.totalPaidAbsenceMinutes = totalPaidAbsenceMinutes;
            this.totalUnpaidAbsenceMinutes = totalUnpaidAbsenceMinutes;
            this.totalCompensatedMinutes = totalCompensatedMinutes;
            this.totalApprovedMinutes = totalApprovedMinutes;
            this.totalQuantity = totalQuantity;
            this.totalScrap = totalScrap;
            this.mealAllowanceNum = mealAllowanceNum;
            this.totalEffective = totalEffective;
        }
    }
}

