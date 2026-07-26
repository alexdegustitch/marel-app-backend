package com.aleksandarparipovic.marel_app.user_table_preferences;

import java.util.Arrays;
import java.util.Locale;

/**
 * The approved registry of table keys.
 *
 * <p>A closed set, so an arbitrary client string can never be stored and later
 * treated as meaningful. The wire form is the kebab-case {@link #getKey()}, which
 * is what the frontend uses to identify a screen.
 */
public enum TableKey {

    PRODUCTION_ORDERS("production-orders"),
    SAMPLE_ORDERS("sample-orders"),
    PRODUCTS("products"),
    OPERATIONS("operations"),
    EMPLOYEES("employees"),
    USERS("users"),
    WORK_LOGS("work-logs"),
    WORK_SHIFTS("work-shifts"),
    DAILY_REPORTS("daily-reports"),
    MONTHLY_REPORTS("monthly-reports"),
    PAYROLL_RUNS("payroll-runs"),
    MANUFACTURING_TIME_REQUESTS("manufacturing-time-requests"),
    REGISTRATION_REQUESTS("registration-requests"),
    MAILING_LISTS("mailing-lists");

    private final String key;

    TableKey(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    /** @throws IllegalArgumentException for anything not in the registry. */
    public static TableKey fromKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(value -> value.key.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nepoznat ključ tabele: " + key));
    }
}
