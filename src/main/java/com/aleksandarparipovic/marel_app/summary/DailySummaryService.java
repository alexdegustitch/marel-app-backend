package com.aleksandarparipovic.marel_app.summary;

import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.summary.dto.DailySummaryDto;
import com.aleksandarparipovic.marel_app.summary.dto.DailySummaryProjection;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.interval.ShiftIntervalResolver;
import com.aleksandarparipovic.marel_app.work_log.interval.WorkIntervalCalculator;
import com.aleksandarparipovic.marel_app.work_log.interval.WorkIntervalInput;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private final WorkLogRepository workLogRepo;
    private final AppSettingService appSettingService;
    private final WorkIntervalCalculator intervalCalculator;
    private final ShiftIntervalResolver intervalResolver;

    /**
     * Fast daily summary read directly from work_logs (no derived tables needed).
     * Used immediately after a work-log mutation so the UI refreshes instantly.
     */
    @Transactional(readOnly = true)
    public DailySummaryDto getDailySummary(Long workShiftId) {
        DailySummaryProjection proj = workLogRepo.getDailySummaryByShift(workShiftId);

        List<WorkLogDto> logs = workLogRepo.getAllActiveLogsForShift(workShiftId);
        List<WorkLog> rawLogs = workLogRepo.findActiveLogsWithRefsForShift(workShiftId);

        LocalDate workDate = rawLogs.isEmpty() || rawLogs.getFirst().getWorkShift() == null
                ? null
                : rawLogs.getFirst().getWorkShift().getWorkDate();
        Metrics metrics = calculateMetrics(rawLogs, workDate);

        if (proj == null) {
            return DailySummaryDto.builder()
                    .workShiftId(workShiftId)
                    .totalShiftMinutes(0)
                    .totalMinutes(metrics.totalMinutes)
                    .performanceRate(metrics.performanceRate)
                    .approvedPerformanceRate(metrics.approvedPerformanceRate)
                    .performanceCoefficient(metrics.performanceCoefficient)
                    .totalWeightedNormMinutes(metrics.totalWeightedNormMinutes)
                    .verifiedMinutes(metrics.verifiedMinutes)
                    .plMinutes(metrics.plMinutes)
                    .plbMinutes(metrics.plbMinutes)
                    .overlappingLogIds(metrics.overlappingLogIds)
                    .logs(logs)
                    .build();
        }

        return DailySummaryDto.builder()
                .workShiftId(proj.getWorkShiftId())
                .employeeId(proj.getEmployeeId())
                .workDate(workDate)
                .totalShiftMinutes(proj.getTotalShiftMinutes())
                .totalMinutes(metrics.totalMinutes)
                .performanceRate(metrics.performanceRate)
                .approvedPerformanceRate(metrics.approvedPerformanceRate)
                .performanceCoefficient(metrics.performanceCoefficient)
                .totalWeightedNormMinutes(metrics.totalWeightedNormMinutes)
                .verifiedMinutes(metrics.verifiedMinutes)
                .plMinutes(metrics.plMinutes)
                .plbMinutes(metrics.plbMinutes)
                .overlappingLogIds(metrics.overlappingLogIds)
                .logs(logs)
                .build();
    }

    private Metrics calculateMetrics(List<WorkLog> logs, LocalDate workDate) {
        if (logs.isEmpty()) {
            return Metrics.empty();
        }

        // Weighted performance/norm sums stay based on each log's own raw duration —
        // unrelated to the displayed "duration" figure, not in scope for dedup here.
        BigDecimal weightedRateSum = BigDecimal.ZERO;
        BigDecimal weightedApprovedRateSum = BigDecimal.ZERO;
        BigDecimal totalWeightedNorm = BigDecimal.ZERO;
        long rawTotalMinutes = 0;

        for (WorkLog log : logs) {
            int duration = safeInt(log.getDurationMin());
            if (duration <= 0) {
                continue;
            }
            rawTotalMinutes += duration;

            BigDecimal perfRate = calculatePerformanceRate(log);
            BigDecimal maxEfficiency = appSettingService.getMaxEfficiencyPercentAt(log.getStartAt());
            BigDecimal approvedRate = perfRate.min(maxEfficiency);

            BigDecimal durationWeight = BigDecimal.valueOf(duration);
            weightedRateSum = weightedRateSum.add(perfRate.multiply(durationWeight));
            weightedApprovedRateSum = weightedApprovedRateSum.add(approvedRate.multiply(durationWeight));

            BigDecimal approvedCoefficient = approvedRate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            totalWeightedNorm = totalWeightedNorm.add(durationWeight.multiply(approvedCoefficient));
        }

        if (rawTotalMinutes == 0) {
            return Metrics.empty();
        }

        // Displayed "duration" is the GLOBAL union of every valid interval, whatever
        // its category: overlapping wall-clock time counts once and gaps not at all.
        // Merging per category and summing the per-category totals — what this used to
        // do — counted a cross-category overlap twice.
        List<WorkIntervalInput> intervals = intervalResolver.toIntervals(logs);
        BigDecimal plbCoefficient = intervalResolver.resolvePlbCoefficient(workDate);
        WorkIntervalCalculator.VerifiedTime verified =
                intervalCalculator.computeVerifiedTime(intervals, plbCoefficient);

        // Flagging cross-category overlaps stays a separate concern from measuring
        // covered time, and is unchanged.
        List<Long> overlappingLogIds = detectCrossCategoryOverlaps(logs);

        BigDecimal divisor = BigDecimal.valueOf(rawTotalMinutes);
        BigDecimal performanceRate = weightedRateSum.divide(divisor, 4, RoundingMode.HALF_UP);
        BigDecimal approvedRate = weightedApprovedRateSum.divide(divisor, 4, RoundingMode.HALF_UP);
        return new Metrics(
                verified.coveredMinutes(),
                performanceRate,
                approvedRate,
                approvedRate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP),
                totalWeightedNorm.setScale(4, RoundingMode.HALF_UP),
                verified.verifiedMinutes(),
                verified.plMinutes(),
                verified.plbMinutes(),
                overlappingLogIds
        );
    }

    /** Flags log ids involved in an overlap with a log from a DIFFERENT work-code category (not auto-merged). */
    private List<Long> detectCrossCategoryOverlaps(List<WorkLog> logs) {
        List<WorkLog> sorted = logs.stream()
                .filter(l -> l.getStartAt() != null && l.getEndAt() != null)
                .sorted(Comparator.comparing(WorkLog::getStartAt))
                .toList();

        Set<Long> conflicting = new LinkedHashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            WorkLog a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                WorkLog b = sorted.get(j);
                if (!b.getStartAt().isBefore(a.getEndAt())) {
                    break; // sorted by start — no later log can overlap "a" either
                }
                Long catA = a.getWorkCode() != null ? a.getWorkCode().getId() : null;
                Long catB = b.getWorkCode() != null ? b.getWorkCode().getId() : null;
                if (!java.util.Objects.equals(catA, catB)) {
                    conflicting.add(a.getId());
                    conflicting.add(b.getId());
                }
            }
        }
        return new ArrayList<>(conflicting);
    }

    private BigDecimal calculatePerformanceRate(WorkLog log) {
        Operation operation = log.getOperation();
        if (operation == null || !operation.isNormRequired()) {
            return BigDecimal.valueOf(100);
        }

        BigDecimal hourlyOutput = log.getHourlyOutput() != null ? log.getHourlyOutput() : BigDecimal.ZERO;
        BigDecimal multiplier = resolveMultiplier(log);
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(100)
                .multiply(hourlyOutput)
                .divide(multiplier, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveMultiplier(WorkLog log) {
        WorkCodeCategory category = log.getWorkCode();
        if (category == null) return BigDecimal.ONE;

        if (category.getCategoryNo() == null || log.getStartAt() == null) {
            return BigDecimal.valueOf(category.getNormMultiplier() == null ? 1d : category.getNormMultiplier());
        }

        return BigDecimal.valueOf(category.getNormMultiplier() == null ? 1d : category.getNormMultiplier());
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private record Metrics(
            long totalMinutes,
            BigDecimal performanceRate,
            BigDecimal approvedPerformanceRate,
            BigDecimal performanceCoefficient,
            BigDecimal totalWeightedNormMinutes,
            BigDecimal verifiedMinutes,
            long plMinutes,
            long plbMinutes,
            List<Long> overlappingLogIds
    ) {
        static Metrics empty() {
            return new Metrics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 0, 0, List.of());
        }
    }
}
