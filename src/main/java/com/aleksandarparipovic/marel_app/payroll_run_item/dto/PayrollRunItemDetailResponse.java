package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class PayrollRunItemDetailResponse {
    private final PayrollRunItemResponse summary;
    private final List<PayrollRunItemCategoryDetailDto> categories;
    private final List<PayrollAdjustmentSectionDto> adjustments;
    private final PayrollRunItemPermissionsDto permissions;

    /**
     * The locale the display names in this response were actually resolved in —
     * the {@code ?locale=} override, else the employee's {@code preferred_locale},
     * else {@link com.aleksandarparipovic.marel_app.common.i18n.AppLocales#DEFAULT}.
     *
     * <p><b>A document renderer must pick its own static labels by THIS value, not
     * by what the user selected.</b> The two differ whenever the request asks for a
     * locale the backend does not ship. Choosing labels by the request while the
     * names came back in another language produces a payslip in two languages;
     * following this field makes that unreachable, because the whole document falls
     * back together.
     */
    private final String resolvedLocale;

    /**
     * Whether this response is missing something the payroll actually contains.
     *
     * <p>True when per-line access dropped a line or withheld a headline figure.
     * The totals on such a response are still correct arithmetic — the same
     * expression with fewer terms — but they are not the payroll's totals, and
     * the screen has to say so next to every one of them rather than let
     * somebody quote a figure as the amount payable.
     *
     * <p>False, not absent, for payroll's own roles: they see everything.
     */
    private final boolean partialView;

    /**
     * Whether the month has worked minutes but no hourly rate in force, so it
     * prices those minutes at zero.
     *
     * <p>Not a masking flag like {@link #partialView}: this says the payroll is
     * missing an INPUT, not that the reader is missing a figure. It is what turns
     * a silent zero on the payslip into something the screen can point at — "no
     * rate; enter one effective from the employee's start date" — rather than a
     * plausible-looking total nobody questions.
     */
    private final boolean missingHourlyRate;
}

