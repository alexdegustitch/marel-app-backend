package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class PayrollAdjustmentSectionDto {
    private final String sectionCode;
    private final Integer sectionOrder;
    private final List<PayrollAdjustmentDetailDto> adjustments;
}

