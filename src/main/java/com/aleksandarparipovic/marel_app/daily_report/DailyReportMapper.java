package com.aleksandarparipovic.marel_app.daily_report;


import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportDto;
import org.springframework.stereotype.Component;

@Component
public class DailyReportMapper {

    public DailyReportDto toDto(DailyReport report){
        DailyReportDto reportDto = new DailyReportDto();
        reportDto.setWorkShiftId(report.getWorkShift().getId());
        reportDto.setEmployeeId(report.getEmployee().getId());
        reportDto.setWorkDate(report.getWorkDate());
        reportDto.setTotalShiftMinutes(report.getTotalShiftMinutes());
        reportDto.setPerformanceRate(report.getPerformanceRate());
        reportDto.setApprovedPerformanceRate(report.getApprovedPerformanceRate());
        reportDto.setPerformanceCoefficient(report.getPerformanceCoefficient());
        reportDto.setApprovedPerformanceCoefficient(report.getApprovedPerformanceCoefficient());
        reportDto.setTotalWeightedNormMinutes(report.getTotalWeightedNormMinutes());
        reportDto.setTotalVerifiedMinutes(report.getTotalVerifiedMinutes());
        reportDto.setTotalPlMinutes(report.getTotalPlMinutes());
        reportDto.setTotalPlbMinutes(report.getTotalPlbMinutes());
        reportDto.setBonusEligibleMinutes(report.getBonusEligibleMinutes());
        reportDto.setIsMealAllowed(report.getIsMealAllowed());
        reportDto.setMealsCount(report.getMealsCount());
        return reportDto;
    }
}
