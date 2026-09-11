package com.aleksandarparipovic.marel_app.work_code.dto;

import java.util.List;

/** The šifarnik edit form's prefill: the version itself plus its step-2 state. */
public record WorkCodeCategoryAdminDetailDto(
        WorkCodeCategoryAdminDto category,
        List<WorkCodeCategorySchemeRuleFormDto> schemeRules
) {
}
