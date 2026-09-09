package com.aleksandarparipovic.marel_app.daily_report;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportChartInfo;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportCreateRequest;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportCreateResponse;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportDto;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportEmployeeMonthlyInfo;
import com.aleksandarparipovic.marel_app.daily_report.dto.MealAdjustmentRequest;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyReportService {

    private final DailyReportRepository dailyReportRepository;
    private final DailyReportMapper mapper;
    private final EntityReferenceProvider referenceProvider;
    private final CurrentUserService currentUserService;
    private final RecalcQueueService recalcQueueService;
    private final PayrollRunItemRepository payrollRunItemRepository;

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

    /**
     * Correct one day's meal count by hand.
     *
     * <p>The delta is STORED BESIDE the computed figure, never instead of it:
     * {@code meals_count} stays recalc-owned, the correction survives every
     * rebuild, and the karton can always show both — "computed 2, by hand −1,
     * paid 1". A delta of 0 clears the correction (note and authorship too),
     * so undoing needs no second endpoint.
     *
     * <p>The month is requeued because {@code monthly_reports.meal_allowance_num}
     * — where the payroll reads — is a sum over the daily EFFECTIVE counts.
     */
    @Transactional
    public DailyReportDto adjustMeals(Long workShiftId, MealAdjustmentRequest request) {
        DailyReport report = dailyReportRepository.findByWorkShiftId(workShiftId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "DailyReport not found for workShiftId: " + workShiftId));

        refuseWhenMonthIsClosed(report);

        int delta = request.delta();
        int computed = report.getMealsCount() != null ? report.getMealsCount() : 0;
        if (computed + delta < 0) {
            throw new ConflictException(
                    "Obračunato je " + computed + " obroka — korekcija od " + delta
                            + " bi dan odvela ispod nule.");
        }

        if (delta == 0) {
            report.setMealsManualDelta(0);
            report.setMealsManualNote(null);
            report.setMealsManualAt(null);
            report.setMealsManualBy(null);
        } else {
            report.setMealsManualDelta(delta);
            String note = request.note();
            report.setMealsManualNote(note != null && !note.isBlank() ? note.trim() : null);
            report.setMealsManualAt(OffsetDateTime.now());
            report.setMealsManualBy(currentUserService.getCurrentUserId());
        }
        dailyReportRepository.save(report);

        LocalDate workDate = report.getWorkDate();
        if (report.getEmployee() != null && workDate != null) {
            recalcQueueService.enqueueMonthlyJob(report.getEmployee(),
                    workDate.getYear(), workDate.getMonthValue(), "MEAL_MANUAL_ADJUSTMENT");
        }

        return mapper.toDto(report);
    }

    /**
     * Same refusal the shift actions make: once the month's payroll is submitted
     * or locked, a meal correction would change a figure somebody already signed.
     */
    private void refuseWhenMonthIsClosed(DailyReport report) {
        LocalDate date = report.getWorkDate();
        if (date == null || report.getEmployee() == null) {
            return;
        }
        long closed = payrollRunItemRepository.countClosedForEmployeeAndMonth(
                report.getEmployee().getId(), date.getYear(), date.getMonthValue());
        if (closed > 0) {
            throw new ConflictException(
                    "Obračun za " + date.getMonthValue() + "/" + date.getYear()
                            + " je predat ili zaključan. Vratite ga na doradu"
                            + " pre izmene toplih obroka.");
        }
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
