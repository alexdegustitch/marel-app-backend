package com.aleksandarparipovic.marel_app.daily_report;

import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportChartInfo;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportCreateRequest;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportCreateResponse;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportDto;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportEmployeeMonthlyInfo;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyReportService {

    private final DailyReportRepository dailyReportRepository;
    private final DailyReportMapper mapper;
    private final EntityReferenceProvider referenceProvider;

    @Transactional(readOnly = true)
    public List<DailyReport> findAll() {
        return dailyReportRepository.findAll();
    }

    @Transactional(readOnly = true)
    public DailyReport findById(Long id) {
        return dailyReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DailyReport not found"));
    }

    @Transactional(readOnly = true)
    public List<DailyReportChartInfo> findChartInfoByEmployeeAndPeriod(Long employeeId, int year, int month) {
        YearMonth period = YearMonth.of(year, month);
        LocalDate startDate = period.atDay(1);
        LocalDate endDate = period.atEndOfMonth();
        return dailyReportRepository.findChartInfoByEmployeeAndPeriod(employeeId, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public List<DailyReportEmployeeMonthlyInfo> findEmployeeMonthlyInfo(Long employeeId, int year, int month) {
        YearMonth period = YearMonth.of(year, month);
        LocalDate startDate = period.atDay(1);
        LocalDate endDate = period.atEndOfMonth();
        return dailyReportRepository.findEmployeeMonthlyInfoByPeriod(employeeId, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public DailyReportDto findByWorkShiftId(Long workShiftId){
        DailyReport report = dailyReportRepository.findByWorkShiftId(workShiftId)
                .orElseThrow(() -> new IllegalArgumentException("DailyReport not found for workShiftId: " + workShiftId));

        return mapper.toDto(report);
    }

    @Transactional
    public DailyReportCreateResponse create(DailyReportCreateRequest request) {
        DailyReport entity = DailyReport.builder()
                .employee(referenceProvider.getRequiredReference(Employee.class, request.getEmployeeId(), "employeeId"))
                .workShift(referenceProvider.getRequiredReference(WorkShift.class, request.getWorkShiftId(), "workShiftId"))
                .workDate(LocalDate.parse(request.getWorkDate()))
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
                .performanceRate(BigDecimal.ZERO)
                .approvedPerformanceRate(BigDecimal.ZERO)
                .performanceCoefficient(BigDecimal.ZERO)
                .approvedPerformanceCoefficient(BigDecimal.ZERO)
                .calcVersion(0)
                .isMealAllowed(false)
                .mealsCount(0)
                .build();

        DailyReport created = dailyReportRepository.save(entity);
        return new DailyReportCreateResponse(created.getId());
    }

    @Transactional
    public DailyReport update(Long id, DailyReport entity) {
        if (!dailyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("DailyReport not found");
        }
        entity.setId(id);
        return dailyReportRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!dailyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("DailyReport not found");
        }
        dailyReportRepository.deleteById(id);
    }
}
