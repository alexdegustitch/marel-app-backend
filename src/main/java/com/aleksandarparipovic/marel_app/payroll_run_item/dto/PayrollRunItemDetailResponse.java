package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class PayrollRunItemDetailResponse {
    private final PayrollRunItemResponse summary;
    private final List<PayrollRunItemCategoryDetailDto> categories;
    private final List<PayrollAdjustmentDetailDto> adjustments;
    private final PayrollRunItemPermissionsDto permissions;
}

