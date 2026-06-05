package com.aleksandarparipovic.marel_app.summary;

import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.summary.dto.MonthlySummaryDto;
import com.aleksandarparipovic.marel_app.summary.dto.MonthlySummaryProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class MonthlySummaryService {

    private final DailyReportRepository dailyReportRepo;

    /**
     * Monthly summary aggregated from daily_reports.
     * May be briefly stale immediately after a work-log mutation (until daily worker runs).
     */
    @Transactional(readOnly = true)
    public MonthlySummaryDto getMonthlySummary(Long employeeId, int year, int month) {
        MonthlySummaryProjection proj =
                dailyReportRepo.getMonthlySummaryFromDailyReports(employeeId, year, month);

        if (proj == null) {
            return MonthlySummaryDto.builder()
                    .employeeId(employeeId)
                    .reportYear(year)
                    .reportMonth(month)
                    .totalShiftMinutes(0)
                    .totalWorkMinutes(0)
                    .totalQuantity(0)
                    .totalScrap(0)
                    .totalEffectiveMinutes(BigDecimal.ZERO)
                    .stale(true)
                    .build();
        }

        BigDecimal effective = proj.getTotalEffectiveMinutes() instanceof Number n
                ? BigDecimal.valueOf(n.doubleValue())
                : BigDecimal.ZERO;

        return MonthlySummaryDto.builder()
                .employeeId(proj.getEmployeeId())
                .reportYear(proj.getReportYear())
                .reportMonth(proj.getReportMonth())
                .totalShiftMinutes(proj.getTotalShiftMinutes() != null ? proj.getTotalShiftMinutes() : 0L)
                .totalWorkMinutes(proj.getTotalWorkMinutes() != null ? proj.getTotalWorkMinutes() : 0L)
                .totalQuantity(proj.getTotalQuantity() != null ? proj.getTotalQuantity() : 0L)
                .totalScrap(proj.getTotalScrap() != null ? proj.getTotalScrap() : 0L)
                .totalEffectiveMinutes(effective)
                .stale(false)
                .build();
    }
}
