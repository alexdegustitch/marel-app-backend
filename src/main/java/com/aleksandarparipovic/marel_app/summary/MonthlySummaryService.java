package com.aleksandarparipovic.marel_app.summary;

import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.summary.dto.MonthlySummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class MonthlySummaryService {

    private final MonthlyReportRepository monthlyReportRepository;
    private final EmployeeRecordService employeeRecordService;

    /**
     * Monthly summary aggregated from daily_reports.
     * May be briefly stale immediately after a work-log mutation (until daily worker runs).
     */
    @Transactional(readOnly = true)
    public MonthlySummaryDto getMonthlySummary(Long employeeId, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        EmployeeRecord employeeRecord = employeeRecordService.getOrCreateMonthlyRecord(employeeId, start);

        MonthlyReport report = monthlyReportRepository
                .findByEmployeeRecord_Id(employeeRecord.getId())
                .orElse(null);

        if (report == null) {
            return MonthlySummaryDto.builder()
                    .employeeId(employeeId)
                    .startDate(start)
                    .endDate(end)
                    .totalShiftMinutes(0)
                    .performanceRate(BigDecimal.ZERO)
                    .approvedPerformanceRate(BigDecimal.ZERO)
                    .totalWeightedNormMinutes(BigDecimal.ZERO)
                    .totalApprovedMinutes(BigDecimal.ZERO)
                    .stale(true)
                    .build();
        }

        return MonthlySummaryDto.builder()
                .employeeId(employeeRecord.getEmployee().getId())
                .startDate(report.getStartDate())
                .endDate(report.getEndDate())
                .totalShiftMinutes(report.getTotalShiftMinutes() != null ? report.getTotalShiftMinutes() : 0L)
                .performanceRate(report.getPerformanceRate() != null ? report.getPerformanceRate() : BigDecimal.ZERO)
                .approvedPerformanceRate(report.getApprovedPerformanceRate() != null ? report.getApprovedPerformanceRate() : BigDecimal.ZERO)
                .totalWeightedNormMinutes(report.getTotalWeightedNormMinutes() != null ? report.getTotalWeightedNormMinutes() : BigDecimal.ZERO)
                .totalApprovedMinutes(report.getTotalApprovedMinutes() != null
                        ? BigDecimal.valueOf(report.getTotalApprovedMinutes())
                        : BigDecimal.ZERO)
                .stale(false)
                .build();
    }
}
