package com.aleksandarparipovic.marel_app.common;

import java.util.Locale;

/**
 * A search fragment turned into a {@code LIKE} pattern.
 *
 * <p>Lower-cased, so it meets the {@code lower(column)} the trigram indexes are
 * built on, and with the three LIKE metacharacters escaped by a backslash —
 * Postgres's default escape character — so a worker number containing an
 * underscore finds that number and not every number.
 */
public final class LikePattern {

    private LikePattern() {
    }

    /** {@code %fragment%}, ready to bind. */
    public static String contains(String fragment) {
        String escaped = fragment
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
