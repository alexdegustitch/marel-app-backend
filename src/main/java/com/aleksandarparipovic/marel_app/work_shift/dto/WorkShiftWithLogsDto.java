package com.aleksandarparipovic.marel_app.work_shift.dto;

import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WorkShiftWithLogsDto(
        Long shiftId,
        LocalDate workDate,
        Long supervisorId,
        String supervisorFullName,
        Instant startAt,
        Instant endAt,
        Integer totalMinutes,
        String note,
        Long employeeId,
        String employeeName,
        List<WorkLogDto> logs
) {}