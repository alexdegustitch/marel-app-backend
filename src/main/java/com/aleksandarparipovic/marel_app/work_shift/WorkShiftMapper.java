package com.aleksandarparipovic.marel_app.work_shift;


import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftBasicInfoDto;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftDto;
import org.springframework.stereotype.Component;

@Component
public class WorkShiftMapper {

    WorkShiftBasicInfoDto toBasicInfoDto(WorkShift workShift){
        return new WorkShiftBasicInfoDto(workShift.getId(), workShift.getStartAt(), workShift.getEndAt(), workShift.getWorkDate());
    }
}
