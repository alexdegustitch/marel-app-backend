package com.aleksandarparipovic.marel_app.app_settings.dto;

import java.util.List;

public record AppSettingHistoryDto(
        String settingKey,
        String valueType,
        Boolean affectsPayroll,
        String description,
        String displayText,
        String unit,
        List<AppSettingResponse> history
) {}

