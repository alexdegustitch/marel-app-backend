package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of the Kartoni month list: the karton's monthly figures plus a
 * glance at its last shifts, assembled in the service from
 * {@link EmployeeRecordInfo} and {@link EmployeeRecordShiftGlance}.
 */
public record EmployeeRecordListRow(
        Long id,
        String employeeName,
        Long employeeId,
        String employeeNo,
        String employeeSchemeCode,
        String employeeDepartment,
        String employeeBonus,
        Instant updateTime,
        Integer totalShiftMinutes,
        BigDecimal totalWeightedNormMinutes,
        BigDecimal approvedPerformanceRate,
        List<ShiftGlance> recentShifts
) {

    public record ShiftGlance(
            Long workShiftId,
            LocalDate workDate,
            String categoryNo,
            String categoryName,
            BigDecimal approvedPerformanceRate
    ) {
        public static ShiftGlance of(EmployeeRecordShiftGlance glance) {
            return new ShiftGlance(
                    glance.getWorkShiftId(),
                    glance.getWorkDate(),
                    glance.getCategoryNo(),
                    glance.getCategoryName(),
                    glance.getApprovedPerformanceRate());
        }
    }

    public static EmployeeRecordListRow of(EmployeeRecordInfo info, List<ShiftGlance> recentShifts) {
        return new EmployeeRecordListRow(
                info.getId(),
                info.getEmployeeName(),
                info.getEmployeeId(),
                info.getEmployeeNo(),
                info.getEmployeeSchemeCode(),
                info.getEmployeeDepartment(),
                info.getEmployeeBonus(),
                info.getUpdateTime(),
                info.getTotalShiftMinutes(),
                info.getTotalWeightedNormMinutes(),
                info.getApprovedPerformanceRate(),
                recentShifts);
    }
}
