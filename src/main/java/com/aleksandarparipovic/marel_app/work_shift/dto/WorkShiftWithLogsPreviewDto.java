package com.aleksandarparipovic.marel_app.work_shift.dto;


import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogPreviewDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WorkShiftWithLogsPreviewDto(
        Long shiftId,
        LocalDate workDate,
        Long supervisorId,
        String supervisorFullName,
        Instant startAt,
        Instant endAt,
        Integer totalMinutes,
        String notes,
        Long employeeId,
        String employeeName,
        List<WorkLogPreviewDto> logs
) {}