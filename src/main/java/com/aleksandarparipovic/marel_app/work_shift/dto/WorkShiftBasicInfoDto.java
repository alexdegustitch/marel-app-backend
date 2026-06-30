package com.aleksandarparipovic.marel_app.work_shift.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record WorkShiftBasicInfoDto(
        Long id,
        Long employeeId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        LocalDate workDate
) {
}
