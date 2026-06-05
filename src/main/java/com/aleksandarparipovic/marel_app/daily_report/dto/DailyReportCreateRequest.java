package com.aleksandarparipovic.marel_app.daily_report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DailyReportCreateRequest {

    @NotNull
    private Long employeeId;

    /** ISO-8601 date string, e.g. "2026-04-24". */
    @NotBlank
    private String workDate;

    @NotNull
    private Long workShiftId;
}

