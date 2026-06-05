package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PayrollRunItemPermissionsDto {
    private final boolean canEditAdjustments;
    private final boolean canLock;
    private final boolean canApprove;
}

