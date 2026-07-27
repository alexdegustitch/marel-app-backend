package com.aleksandarparipovic.marel_app.common.i18n;

import java.util.Set;

/**
 * The locales the application stores translations for.
 *
 * <p>{@link #DEFAULT} is served from the master table's own name column
 * ({@code work_code_categories.category_name},
 * {@code payroll_adjustment_categories.name}) unless an administrator has
 * explicitly added an override row. That keeps one editable place for the
 * Serbian name instead of two that can drift apart.
 *
 * <p>Locale never affects a calculated amount. It selects a display name and
 * nothing else.
 */
public final class AppLocales {

    public static final String DEFAULT = "sr-Latn";
    public static final String ENGLISH = "en";

    public static final Set<String> SUPPORTED = Set.of(DEFAULT, ENGLISH);

    private AppLocales() {
    }

    /**
     * Normalises a requested locale to one this application supports, falling
     * back to {@link #DEFAULT} for null, blank or unknown input.
     *
     * <p>Deliberately forgiving: an unrecognised {@code ?locale=} parameter should
     * render the document in the default language, not fail the request. Matching
     * is case-insensitive so {@code EN} and {@code en} are the same locale, which
     * is also what the unique indexes on the translation tables enforce.
     */
    public static String normalize(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT;
        }
        String trimmed = requested.trim();
        return SUPPORTED.stream()
                .filter(supported -> supported.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(DEFAULT);
    }

    public static boolean isDefault(String locale) {
        return DEFAULT.equalsIgnoreCase(normalize(locale));
    }
}
