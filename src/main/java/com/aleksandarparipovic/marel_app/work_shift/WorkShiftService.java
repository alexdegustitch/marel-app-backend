package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.report_worker.DailyRecalcRequestedEvent;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogPreviewDto;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.dto.*;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkShiftService {

    private final WorkShiftRepository repository;
    private final CurrentUserService currentUserService;
    private final WorkLogRepository workLogRepository;
    private final WorkShiftMapper workShiftMapper;
    private final ShiftRepository shiftRepository;
    private final EmployeeRecordService employeeRecordService;
    private final EntityReferenceProvider referenceProvider;
    private final RecalcQueueService recalcQueueService;
    private final ApplicationEventPublisher eventPublisher;
    private final DailyReportRepository dailyReportRepository;

    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");


    public WorkShiftBasicInfoDto getWorkShiftById(Long id){
        return repository.findById(id)
                .map(workShiftMapper::toBasicInfoDto)
                .orElseThrow(() -> new EntityNotFoundException("Shift not found"));
    }

    public WorkShiftInfoDto getWorkShiftInfo(Long id) {
        WorkShift ws = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Shift not found"));
        DailyReport dr = dailyReportRepository.findByWorkShiftId(id).orElse(null);
        return workShiftMapper.toInfoDto(ws, dr);
    }

    public List<WorkShiftActivityDto> findLastThreePerMonthForSupervisor(int year){
        Long userId = currentUserService.getCurrentUserId();
        return repository.findLastThreePerMonthForSupervisor(userId, year);
    }

    public Page<WorkShiftInfo> getWorkShiftsByYearAndMonth(
            Integer year,
            Integer month,
            String search,
            Pageable pageable
    ) {
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);

        return repository.findMonthlyShifts(start, end, search, pageable);
    }


    public List<WorkShiftWithLogsPreviewDto> getShiftsPreviewForEmployee(Long employeeId, int year, int month){
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);
        List<WorkShiftDetailInfo> shifts =
                repository.getShiftsForMonth(employeeId, start, end);

        List<Long> shiftIds = shifts.stream()
                .map(WorkShiftDetailInfo::getId)
                .toList();

        List<WorkLogPreviewDto> logs =
                workLogRepository.getLogsPreviewForShifts(shiftIds);

        Map<Long, List<WorkLogPreviewDto>> logsByShift =
                logs.stream()
                        .collect(Collectors.groupingBy(WorkLogPreviewDto::getShiftId));

        return shifts.stream()
                .map(shift -> new WorkShiftWithLogsPreviewDto(
                        shift.getId(),
                        shift.getWorkDate(),
                        shift.getSupervisorId(),
                        shift.getSupervisorFullName(),
                        shift.getStartAt(),
                        shift.getEndAt(),
                        shift.getTotalMinutes(),
                        shift.getNote(),
                        shift.getEmployeeId(),
                        shift.getEmployeeName(),
                        logsByShift.getOrDefault(shift.getId(), List.of())
                ))
                .toList();
    }

    public List<WorkShiftWithLogsDto> getShiftsForEmployee(Long employeeId, int year, int month){
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);
        List<WorkShiftDetailInfo> shifts =
                repository.getShiftsForMonth(employeeId, start, end);

        List<Long> shiftIds = shifts.stream()
                .map(WorkShiftDetailInfo::getId)
                .toList();

        List<WorkLogDto> logs =
                workLogRepository.getLogsForShifts(shiftIds);

        Map<Long, List<WorkLogDto>> logsByShift =
                logs.stream()
                        .collect(Collectors.groupingBy(WorkLogDto::getShiftId));

        return shifts.stream()
                .map(shift -> new WorkShiftWithLogsDto(
                        shift.getId(),
                        shift.getWorkDate(),
                        shift.getSupervisorId(),
                        shift.getSupervisorFullName(),
                        shift.getStartAt(),
                        shift.getEndAt(),
                        shift.getTotalMinutes(),
                        shift.getNote(),
                        shift.getEmployeeId(),
                        shift.getEmployeeName(),
                        logsByShift.getOrDefault(shift.getId(), List.of())
                ))
                .toList();
    }

    public List<Long> getShiftsForEmployeeRecord(Long employeeRecordId) {
        return repository.getShiftsForEmployeeRecord(employeeRecordId);
    }

    public List<Long> getShiftsForEmployeeRecord(Long employeeRecordId, LocalDate fromDate, LocalDate toDate) {
        if (fromDate != null && toDate != null) {
            LocalDate start = fromDate.isBefore(toDate) ? fromDate : toDate;
            LocalDate end = fromDate.isBefore(toDate) ? toDate : fromDate;
            return repository.getShiftsForEmployeeRecordInDateRange(employeeRecordId, start, end);
        }

        if (fromDate != null || toDate != null) {
            LocalDate exactDate = fromDate != null ? fromDate : toDate;
            return repository.getShiftsForEmployeeRecordOnDate(employeeRecordId, exactDate);
        }

        return repository.getShiftsForEmployeeRecord(employeeRecordId);
    }

    @Transactional
    public WorkShiftBasicInfoDto createShift(WorkShiftCreateRequest request) {
        LocalDate workDate = LocalDate.parse(request.getWorkDate());

        Shift shift = shiftRepository.findById(request.getShiftType())
                .orElseThrow(() -> new EntityNotFoundException("Shift not found: " + request.getShiftType()));

        OffsetDateTime startAt = LocalDateTime.of(workDate, shift.getStartTime())
                .atZone(ZONE).toOffsetDateTime();
        OffsetDateTime endAt = LocalDateTime.of(workDate, shift.getEndTime())
                .atZone(ZONE).toOffsetDateTime();

        // Handle overnight shifts
        if (endAt.isBefore(startAt) || endAt.isEqual(startAt)) {
            endAt = endAt.plusDays(1);
        }

        EmployeeRecord employeeRecord = employeeRecordService.getOrCreateMonthlyRecord(request.getEmployeeId(), workDate);

        WorkShift workShift = WorkShift.builder()
                .employee(referenceProvider.getRequiredReference(Employee.class, request.getEmployeeId(), "employeeId"))
                .employeeRecord(employeeRecord)
                .shift(shift)
                .supervisor(referenceProvider.getRequiredReference(User.class, request.getSupervisorId(), "supervisorId"))
                .workCodeCategory(referenceProvider.getRequiredReference(WorkCodeCategory.class, request.getWorkCategoryCodeId(), "workCategoryCodeId"))
                .startAt(startAt)
                .endAt(endAt)
                .workDate(workDate)
                .isActive(true)
                .build();

        return workShiftMapper.toBasicInfoDto(repository.save(workShift));
    }

    @Transactional
    public WorkShiftBasicInfoDto updateShift(Long workShiftId, UpdateWorkShiftRequest request) {
        WorkShift workShift = repository.findById(workShiftId)
                .orElseThrow(() -> new EntityNotFoundException("Work shift not found: " + workShiftId));

        // Update supervisor
        workShift.setSupervisor(referenceProvider.getRequiredReference(User.class, request.getSupervisorId(), "supervisorId"));

        // Update work code category
        workShift.setWorkCodeCategory(referenceProvider.getRequiredReference(WorkCodeCategory.class, request.getWorkCategoryCodeId(), "workCategoryCodeId"));

        // Apply note value directly; null explicitly clears the note.
        workShift.setNote(request.getNotes());
        
        Shift shift = shiftRepository.findById(request.getShiftId())
                .orElseThrow(() -> new EntityNotFoundException("Shift not found: " + request.getShiftId()));

        OffsetDateTime startAt = LocalDateTime.of(workShift.getWorkDate(), shift.getStartTime())
                .atZone(ZONE)
                .toOffsetDateTime();
        OffsetDateTime endAt = LocalDateTime.of(workShift.getWorkDate(), shift.getEndTime())
                .atZone(ZONE)
                .toOffsetDateTime();

        // Keep overnight shifts spanning into the next day.
        if (endAt.isBefore(startAt) || endAt.isEqual(startAt)) {
            endAt = endAt.plusDays(1);
        }

        workShift.setShift(shift);
        workShift.setStartAt(startAt);
        workShift.setEndAt(endAt);

        WorkShift updated = repository.save(workShift);

        // Trigger recalculation if requested
        if (Boolean.TRUE.equals(request.getTriggerRecalculation())) {
            recalcQueueService.enqueueDailyJob(workShift, "WORK_SHIFT_UPDATE");
            eventPublisher.publishEvent(new DailyRecalcRequestedEvent(DailyRecalcRequestedEvent.Type.DAILY));
        }

        return workShiftMapper.toBasicInfoDto(updated);
    }

    /**
     * Keeps the shift's startAt/endAt in sync with its work logs: the boundary
     * expands to cover the earliest log start / latest log end, and shrinks back
     * toward the shift template's default start/end as logs are edited or removed.
     * Always recomputed from scratch (template ⨉ remaining active logs), so deleting
     * the log that caused an earlier expansion correctly pulls the boundary back in.
     * Note: this is the shift's clock-in/clock-out *window*, not "worked minutes" —
     * it intentionally falls back to the template's default span when no logs exist.
     */
    @Transactional
    public void recalculateShiftBoundaries(WorkShift workShift) {
        Shift shiftTemplate = workShift.getShift();

        OffsetDateTime templateStart = LocalDateTime.of(workShift.getWorkDate(), shiftTemplate.getStartTime())
                .atZone(ZONE).toOffsetDateTime();
        OffsetDateTime templateEnd = LocalDateTime.of(workShift.getWorkDate(), shiftTemplate.getEndTime())
                .atZone(ZONE).toOffsetDateTime();
        if (!templateEnd.isAfter(templateStart)) {
            templateEnd = templateEnd.plusDays(1);
        }

        WorkLogRepository.ActiveLogBounds bounds = workLogRepository.findActiveBoundsForShift(workShift.getId());

        OffsetDateTime newStart = templateStart;
        OffsetDateTime newEnd = templateEnd;
        if (bounds != null) {
            if (bounds.getMinStart() != null && bounds.getMinStart().isBefore(newStart)) {
                newStart = bounds.getMinStart();
            }
            if (bounds.getMaxEnd() != null && bounds.getMaxEnd().isAfter(newEnd)) {
                newEnd = bounds.getMaxEnd();
            }
        }

        if (!newStart.isEqual(workShift.getStartAt()) || !newEnd.isEqual(workShift.getEndAt())) {
            workShift.setStartAt(newStart);
            workShift.setEndAt(newEnd);
            repository.save(workShift);
        }
    }
}
