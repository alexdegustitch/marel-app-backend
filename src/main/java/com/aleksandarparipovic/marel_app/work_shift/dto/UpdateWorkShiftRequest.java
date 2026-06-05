package com.aleksandarparipovic.marel_app.work_shift.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateWorkShiftRequest {

    @NotNull
    private Long shiftId;

    @NotNull
    private Long workCategoryCodeId;

    @NotNull
    private Long supervisorId;

    private String notes;

    private Boolean triggerRecalculation;
}

