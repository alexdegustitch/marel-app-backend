package com.aleksandarparipovic.marel_app.monthly_report;

import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.monthly_report.dto.MonthlyReportByEmployeeRecordResponse;
import com.aleksandarparipovic.marel_app.monthly_report.dto.MonthlyReportCreateRequest;
import com.aleksandarparipovic.marel_app.monthly_report.dto.MonthlyReportCreateResponse;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRunInitializationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    private final MonthlyReportRepository monthlyReportRepository;
    private final EntityReferenceProvider referenceProvider;
    private final EmployeeRecordService employeeRecordService;
    private final EmployeeRecordRepository employeeRecordRepository;
    private final PayrollRunInitializationService payrollRunInitializationService;

    @Transactional(readOnly = true)
    public List<MonthlyReport> findAll() {
        return monthlyReportRepository.findAll();
    }

    @Transactional(readOnly = true)
    public MonthlyReport findById(Long id) {
        return monthlyReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MonthlyReport not found"));
    }

    @Transactional(readOnly = true)
    public List<MonthlyReport> findAllByEmployeeIdAndYearAndMonth(Long employeeId, int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();
        return monthlyReportRepository.findAllByEmployeeRecord_Employee_IdAndStartDateAndEndDate(employeeId, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public MonthlyReportByEmployeeRecordResponse findByEmployeeRecordId(Long employeeRecordId) {
        MonthlyReport report = monthlyReportRepository.findByEmployeeRecord_Id(employeeRecordId)
                .orElseThrow(() -> new IllegalArgumentException("MonthlyReport not found for employeeRecordId"));

        return toByEmployeeRecordResponse(report);
    }

    @Transactional(readOnly = true)
    public Optional<MonthlyReportByEmployeeRecordResponse> findPreviousMonthByEmployeeRecordId(Long employeeRecordId) {
        EmployeeRecord currentRecord = employeeRecordRepository.findById(employeeRecordId)
                .orElseThrow(() -> new IllegalArgumentException("EmployeeRecord not found"));

        LocalDate previousMonthStart = YearMonth.from(currentRecord.getStartDate())
                .minusMonths(1)
                .atDay(1);

        return monthlyReportRepository
                .findByEmployeeIdAndEmployeeRecordStartDate(currentRecord.getEmployee().getId(), previousMonthStart)
                .map(this::toByEmployeeRecordResponse);
    }

    @Transactional
    public MonthlyReportCreateResponse create(MonthlyReportCreateRequest request) {
        YearMonth yearMonth = YearMonth.of(request.getYear(), request.getMonth());
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        EmployeeRecord monthRecord = employeeRecordService.getOrCreateMonthlyRecord(request.getEmployeeId(), startDate);
        if (!monthRecord.getId().equals(request.getEmployeeRecordId())) {
            throw new IllegalArgumentException("employeeRecordId does not belong to employeeId/year/month");
        }

        MonthlyReport entity = MonthlyReport.builder()
                .employeeRecord(referenceProvider.getRequiredReference(EmployeeRecord.class, monthRecord.getId(), "employeeRecordId"))
                .startDate(startDate)
                .endDate(endDate)
                .totalShiftMinutes(0)
                .totalWorkMinutes(0)
                .totalAbsencePaidMinutes(0)
                .totalAbsenceUnpaidMinutes(0)
                .totalAbsenceMinutes(0)
                .totalSickLeavePaidMinutes(0)
                .totalSickLeaveUnpaidMinutes(0)
                .totalSickLeaveMinutes(0)
                .totalApprovedMinutes(0)
                .totalQuantity(0)
                .totalScrap(0)
                .totalWeightedNormMinutes(BigDecimal.ZERO)
                .mealAllowanceNum(0)
                .calcVersion(0)
                .version(0)
                .status("OPEN")
                .build();

        MonthlyReport created = monthlyReportRepository.save(entity);

        // Initialize matching PayrollRunItem + categories + adjustments if a PayrollRun
        // already exists for this month. Runs in the same transaction — safe because
        // createPayrollRunItems/createAdjustments use SKIP LOCKED-safe upsert logic.
        payrollRunInitializationService.initializePayrollForMonthlyReport(created, null);

        return new MonthlyReportCreateResponse(created.getId());
    }

    @Transactional
    public MonthlyReport update(Long id, MonthlyReport entity) {
        if (!monthlyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("MonthlyReport not found");
        }
        entity.setId(id);
        return monthlyReportRepository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        if (!monthlyReportRepository.existsById(id)) {
            throw new IllegalArgumentException("MonthlyReport not found");
        }
        monthlyReportRepository.deleteById(id);
    }

    private MonthlyReportByEmployeeRecordResponse toByEmployeeRecordResponse(MonthlyReport report) {
        return MonthlyReportByEmployeeRecordResponse.builder()
                .id(report.getId())
                .totalShiftMinutes(report.getTotalShiftMinutes())
                .totalAbsenceMinutes(report.getTotalAbsenceMinutes())
                .totalSickLeaveMinutes(report.getTotalSickLeaveMinutes())
                .totalWeightedNormMinutes(report.getTotalWeightedNormMinutes())
                .approvedPerformanceRate(report.getApprovedPerformanceRate())
                .mealAllowanceNum(report.getMealAllowanceNum())
                .build();
    }
}
