package com.aleksandarparipovic.marel_app.dashboard.insight;

/**
 * A {@code dashboard_*} app_setting was saved.
 *
 * <p>Raised by {@link com.aleksandarparipovic.marel_app.app_settings.AppSettingService}
 * so the snapshot can follow the new criterion at once — a threshold whose
 * change only takes effect tomorrow morning would look like a change that did
 * not work.
 */
public record DashboardSettingsChangedEvent(String settingKey) {
}
