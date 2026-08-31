package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PayrollRunItemPermissionsDto {
    private final boolean canEditAdjustments;

    /**
     * Whether THIS reader may change THIS payroll at all, right now.
     *
     * <p>The exact two guards {@code PayrollRunItemService.patch} applies, sent
     * so the screen can stop offering controls the server is going to refuse: a
     * LOCKED month is closed to everybody, and a submitted one is closed to the
     * supervisor who submitted it — they said it was finished, payroll started
     * from that, and the way back is a change request.
     *
     * <p>Deliberately NOT {@link #canEditAdjustments}, which is narrower (DRAFT
     * only, for everybody) and answers a different question. Payroll may edit a
     * submitted month; this is what says so.
     */
    private final boolean canEditItem;
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

