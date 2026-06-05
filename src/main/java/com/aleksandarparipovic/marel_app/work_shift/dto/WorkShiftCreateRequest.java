package com.aleksandarparipovic.marel_app.work_shift.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkShiftCreateRequest {

    @NotNull
    private Long employeeId;

    /** ISO-8601 datetime string from the client (e.g. "2026-04-11T00:00:00.000Z"). */
    @NotBlank
    private String workDate;

    /** ID of the Shift definition (shift type). */
    @NotNull
    private Long shiftType;

    /** Work code category id — carried along from the form. */
    @NotNull
    private Long workCategoryCodeId;

    @NotNull
    private Long supervisorId;
}

