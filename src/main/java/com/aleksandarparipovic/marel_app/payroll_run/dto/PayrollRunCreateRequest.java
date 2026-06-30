package com.aleksandarparipovic.marel_app.payroll_run.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayrollRunCreateRequest {

    @NotNull
    @Min(2000)
    private Integer reportYear;

    @NotNull
    @Min(1)
    @Max(12)
    private Integer reportMonth;
}


