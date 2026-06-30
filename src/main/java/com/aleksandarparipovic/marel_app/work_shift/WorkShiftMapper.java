package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftBasicInfoDto;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftInfoDto;
import org.springframework.stereotype.Component;

@Component
public class WorkShiftMapper {

    WorkShiftBasicInfoDto toBasicInfoDto(WorkShift workShift){
        return new WorkShiftBasicInfoDto(workShift.getId(), workShift.getEmployee().getId(), workShift.getStartAt(), workShift.getEndAt(), workShift.getWorkDate());
    }

    WorkShiftInfoDto toInfoDto(WorkShift ws, DailyReport dr) {
        var supervisor = ws.getSupervisor();
        var wcc = ws.getWorkCodeCategory();
        var shift = ws.getShift();
        return new WorkShiftInfoDto(
                ws.getId(),
                ws.getWorkDate(),
                supervisor != null ? supervisor.getId() : null,
                supervisor != null ? supervisor.getFullName() : null,
                wcc != null ? wcc.getId() : null,
                wcc != null ? wcc.getCategoryNo() : null,
                wcc != null ? wcc.getType() : null,
                wcc != null ? wcc.getNormMultiplier() : null,
                shift != null ? shift.getId() : null,
                shift != null ? shift.getShiftCode() : null,
                ws.getStartAt(),
                ws.getEndAt(),
                ws.getNote(),
                // daily report fields
                dr != null ? dr.getId() : null,
                dr != null ? dr.getTotalShiftMinutes() : null,
                dr != null ? dr.getTotalWorkMinutes() : null,
                dr != null ? dr.getTotalAbsencePaidMinutes() : null,
                dr != null ? dr.getTotalAbsenceUnpaidMinutes() : null,
                dr != null ? dr.getTotalSickLeavePaidMinutes() : null,
                dr != null ? dr.getTotalSickLeaveUnpaidMinutes() : null,
                dr != null ? dr.getTotalCompensatedMinutes() : null,
                dr != null ? dr.getTotalApprovedMinutes() : null,
                dr != null ? dr.getTotalQuantity() : null,
                dr != null ? dr.getTotalScrap() : null,
                dr != null ? dr.getTotalWeightedNormMinutes() : null,
                dr != null ? dr.getPerformanceRate() : null,
                dr != null ? dr.getApprovedPerformanceRate() : null,
                dr != null ? dr.getPerformanceCoefficient() : null,
                dr != null ? dr.getApprovedPerformanceCoefficient() : null
        );
    }
}
