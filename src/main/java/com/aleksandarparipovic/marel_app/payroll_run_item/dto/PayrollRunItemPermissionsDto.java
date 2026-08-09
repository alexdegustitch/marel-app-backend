package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PayrollRunItemPermissionsDto {
    private final boolean canEditAdjustments;
    private final boolean canLock;
    private final boolean canApprove;

    /**
     * The two item-level money figures, answered per reader.
     *
     * <p>They are not adjustment lines, so per-line access cannot carry them;
     * they have their own codes in the same table. False means the screen shows
     * the value and no way in — the server refuses the write either way, and
     * this is so nobody types into a field that was never going to save.
     */
    private final boolean canEditHourlyRate;
    private final boolean canEditTotalNetEarnings;
}

