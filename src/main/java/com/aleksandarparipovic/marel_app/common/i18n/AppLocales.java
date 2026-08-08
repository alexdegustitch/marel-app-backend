package com.aleksandarparipovic.marel_app.common.i18n;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 *
 * <p><b>The supported set is shipped, not configured.</b> It must match, in the
 * same pull request, three places:
 * <ol>
 *   <li>{@link #SUPPORTED} here;</li>
 *   <li>the {@code chk_employees_preferred_locale} CHECK constraint;</li>
 *   <li>the JSON label resources in the frontend
 *       ({@code src/ui/features/payrolls/i18n/}).</li>
 * </ol>
 * Adding a locale to only the first two produces a payslip whose category names
 * are translated and whose own labels are not — the half-translated document
 * {@code docs/business-rules/i18n-obracun.md} exists to prevent.
 */
public final class AppLocales {

    public static final String DEFAULT = "sr-Latn";
    public static final String ENGLISH = "en";

    /**
     * Iteration order is the display order, so this is a {@link LinkedHashSet}
     * rather than {@code Set.of}, whose order is unspecified. {@link #normalize}
     * relies on it when several supported locales share a language.
     */
    public static final Set<String> SUPPORTED = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(DEFAULT, ENGLISH)));

    private AppLocales() {
    }

    /**
     * Normalises a requested locale to one this application supports, falling
     * back to {@link #DEFAULT} for null, blank or unknown input.
     *
     * <p><b>For read paths only.</b> Deliberately forgiving: an unrecognised
     * {@code ?locale=} parameter should render the document in the default
     * language, not fail the request. Write paths must call {@link #isSupported}
     * first — silently turning a request to store {@code "ru"} into
     * {@code "sr-Latn"} would put the wrong language on an employee's record.
     *
     * <p>Resolution, most specific first:
     * <ol>
     *   <li>exact match, case-insensitively, {@code _} treated as {@code -};</li>
     *   <li>the region subtag dropped — {@code en-GB} and {@code en-US} are both
     *       {@code en}, {@code sr-Latn-RS} is {@code sr-Latn};</li>
     *   <li>the script subtag dropped too. With exactly one supported locale in
     *       that language it is that one; with several, {@link #DEFAULT} if it is
     *       among them, otherwise the first in display order. This is what makes
     *       the script-less {@code sr} resolve to {@code sr-Latn} <em>without a
     *       rule naming it</em>: if the default ever became Cyrillic, {@code sr}
     *       would follow with no code change.</li>
     * </ol>
     */
    public static String normalize(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT;
        }
        String cleaned = requested.trim().replace('_', '-');

        String exact = matchIgnoringCase(cleaned);
        if (exact != null) {
            return exact;
        }

        Tag tag = Tag.parse(cleaned);

        String withoutRegion = matchIgnoringCase(tag.withoutRegion());
        if (withoutRegion != null) {
            return withoutRegion;
        }

        List<String> sameLanguage = SUPPORTED.stream()
                .filter(supported -> Tag.parse(supported).language().equals(tag.language()))
                .toList();
        if (sameLanguage.size() == 1) {
            return sameLanguage.getFirst();
        }
        if (sameLanguage.contains(DEFAULT)) {
            return DEFAULT;
        }
        if (!sameLanguage.isEmpty()) {
            return sameLanguage.getFirst();
        }

        return DEFAULT;
    }

    /**
     * Whether this is a locale the application ships, compared case-insensitively
     * and accepting {@code _} for {@code -}.
     *
     * <p><b>For write paths.</b> Strict about shape on purpose: {@code en-US} is
     * not supported, it merely {@link #normalize normalises} to one that is.
     * Storing a regional variant would create a second spelling of the same
     * locale that the translation tables' unique indexes could not catch.
     *
     * <p>Callers that accept the value should store {@link #normalize} of it, so
     * what lands in the column is the canonical spelling rather than whatever
     * casing the client sent.
     */
    public static boolean isSupported(String code) {
        return code != null && !code.isBlank()
                && matchIgnoringCase(code.trim().replace('_', '-')) != null;
    }

    public static boolean isDefault(String locale) {
        return DEFAULT.equalsIgnoreCase(normalize(locale));
    }

    /** The supported locale equal to {@code candidate} ignoring case, or null. */
    private static String matchIgnoringCase(String candidate) {
        return SUPPORTED.stream()
                .filter(supported -> supported.equalsIgnoreCase(candidate))
                .findFirst()
                .orElse(null);
    }

    /**
     * The three BCP 47 subtags this application cares about.
     *
     * <p>Identified by shape rather than by position, which is how BCP 47 itself
     * distinguishes them: a four-letter subtag is a script, a two-letter or
     * three-digit one is a region. Parsing by position would read the {@code Latn}
     * in {@code sr-Latn} as a region and strip it.
     */
    private record Tag(String language, String script, String region) {

        static Tag parse(String raw) {
            String[] parts = raw.split("-");
            // A string of nothing but separators splits to an empty array. The
            // empty language that produces matches no supported locale, so the
            // caller falls back — which is the point: normalize() must not throw
            // on garbage, because a payslip still has to print.
            String language = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";
            String script = null;
            String region = null;

            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                if (script == null && part.length() == 4 && isAllLetters(part)) {
                    script = Character.toUpperCase(part.charAt(0))
                            + part.substring(1).toLowerCase(Locale.ROOT);
                } else if (region == null && part.length() == 2 && isAllLetters(part)) {
                    region = part.toUpperCase(Locale.ROOT);
                } else if (region == null && part.length() == 3 && isAllDigits(part)) {
                    region = part;
                }
            }
            return new Tag(language, script, region);
        }

        String withoutRegion() {
            return script == null ? language : language + "-" + script;
        }

        private static boolean isAllLetters(String s) {
            return s.chars().allMatch(Character::isLetter);
        }

        private static boolean isAllDigits(String s) {
            return s.chars().allMatch(Character::isDigit);
        }
    }
}
