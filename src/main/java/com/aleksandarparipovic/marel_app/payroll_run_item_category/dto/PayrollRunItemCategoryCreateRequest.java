package com.aleksandarparipovic.marel_app.payroll_run_item_category.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayrollRunItemCategoryCreateRequest {

    @NotNull
    private Long payrollRunItemId;

    @NotNull
    private Long workCodeCategoryId;
}

