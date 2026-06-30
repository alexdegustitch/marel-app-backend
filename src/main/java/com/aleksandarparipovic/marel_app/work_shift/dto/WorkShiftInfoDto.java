package com.aleksandarparipovic.marel_app.work_shift.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record WorkShiftInfoDto(
        Long workShiftId,
        LocalDate workDate,
        Long supervisorId,
        String supervisorFullName,
        Long workCategoryCodeId,
        String workCategoryCode,
        String workCategoryType,
        Double normMultiplier,
        Long shiftId,
        String shiftCode,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String notes,
        // daily report fields
        Long dailyReportId,
        Integer totalShiftMinutes,
        Integer totalWorkMinutes,
        Integer totalAbsencePaidMinutes,
        Integer totalAbsenceUnpaidMinutes,
        Integer totalSickLeavePaidMinutes,
        Integer totalSickLeaveUnpaidMinutes,
        Integer totalCompensatedMinutes,
        Integer totalApprovedMinutes,
        Integer totalQuantity,
        Integer totalScrap,
        BigDecimal totalWeightedNormMinutes,
        BigDecimal performanceRate,
        BigDecimal approvedPerformanceRate,
        BigDecimal performanceCoefficient,
        BigDecimal approvedPerformanceCoefficient
) {
}
