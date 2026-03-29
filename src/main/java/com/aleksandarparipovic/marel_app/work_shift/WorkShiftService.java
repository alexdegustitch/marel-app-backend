package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogPreviewDto;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.dto.*;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
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


    public WorkShiftBasicInfoDto getWorkShiftById(Long id){
        return repository.findById(id)
                .map(workShiftMapper::toBasicInfoDto)
                .orElseThrow(() -> new EntityNotFoundException("Shift not found"));
    }

    public List<WorkShiftDto> findLastThreePerMonthForSupervisor(int year){
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
                        shift.getNotes(),
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
                        shift.getNotes(),
                        shift.getEmployeeId(),
                        shift.getEmployeeName(),
                        logsByShift.getOrDefault(shift.getId(), List.of())
                ))
                .toList();
    }

}
