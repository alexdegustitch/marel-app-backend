package com.aleksandarparipovic.marel_app.user_preferences.dto;

import com.aleksandarparipovic.marel_app.user_preferences.UiDensity;
import com.aleksandarparipovic.marel_app.user_preferences.UserTheme;

public record UserPreferencesResponse(
        Long userId,
        UserTheme theme,
        String language,
        String timezone,
        String dateFormat,
        String timeFormat,
        String numberFormat,
        UiDensity uiDensity,
        Integer rowsPerPage,
        Boolean sidebarCollapsed,
        Boolean emailNotificationsEnabled,
        Boolean inAppNotificationsEnabled,
        java.util.Map<String, Object> uiSettings
) {
}
