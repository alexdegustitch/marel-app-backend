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
}

