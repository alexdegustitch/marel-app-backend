package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locale normalisation. A unit test, not an IT: no database is involved, so this
 * runs under Surefire in the fast loop.
 *
 * <p>What the resolution rules must guarantee is that a request never fails and
 * never lands on a locale nobody asked for. The payslip is the only consumer, and
 * it has to print.
 */
class AppLocalesTest {

    @Test
    @DisplayName("the default is Serbian Latin and it is one of the supported locales")
    void defaultIsSupported() {
        assertThat(AppLocales.DEFAULT).isEqualTo("sr-Latn");
        assertThat(AppLocales.SUPPORTED).contains(AppLocales.DEFAULT);
    }

    @Test
    @DisplayName("SUPPORTED iterates in display order, which normalize() depends on")
    void supportedIsOrdered() {
        // Set.of would satisfy contains() but not this: its iteration order is
        // unspecified, and the language-only fallback picks "the first" one.
        assertThat(AppLocales.SUPPORTED).containsExactly("sr-Latn", "en");
    }

    // ─── exact match ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "sr-Latn, sr-Latn",
            "SR-LATN, sr-Latn",
            "sr-latn, sr-Latn",
            "sr_Latn, sr-Latn",   // Java's Locale.toString() spelling
            "'  en  ', en",
            "EN,       en",
    })
    @DisplayName("an exact tag resolves to its canonical spelling, whatever the casing")
    void exactMatch(String requested, String expected) {
        assertThat(AppLocales.normalize(requested)).isEqualTo(expected);
    }

    // ─── region dropped ─────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "en-US,       en",
            "en-GB,       en",
            "en-AU,       en",
            "sr-Latn-RS,  sr-Latn",
            "sr_Latn_RS,  sr-Latn",
    })
    @DisplayName("a regional variant resolves to the locale it varies from")
    void regionIsDropped(String requested, String expected) {
        assertThat(AppLocales.normalize(requested)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a four-letter subtag is read as a script, not stripped as a region")
    void scriptIsNotMistakenForRegion() {
        // Parsing by position would treat "Latn" as the region and reduce
        // sr-Latn to sr, which is exactly the ambiguity this application refuses
        // to store.
        assertThat(AppLocales.normalize("sr-Latn")).isEqualTo("sr-Latn");
        assertThat(AppLocales.normalize("sr-Cyrl")).isEqualTo("sr-Latn"); // not shipped yet — falls back
    }

    // ─── script dropped: the "sr" question ──────────────────────────────────

    @Test
    @DisplayName("the script-less 'sr' resolves to the default Serbian, by rule and not by name")
    void scriptlessSerbian() {
        // No entry anywhere says sr -> sr-Latn. It follows from "several supported
        // locales share this language, so use the default". If the default ever
        // became Cyrillic, this would follow with no code change.
        assertThat(AppLocales.normalize("sr")).isEqualTo("sr-Latn");
        assertThat(AppLocales.normalize("SR")).isEqualTo("sr-Latn");
        assertThat(AppLocales.normalize("sr-RS")).isEqualTo("sr-Latn");
    }

    // ─── unknown input never fails ──────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"de", "ru", "xx", "zh-Hans", "?!", "-", "---", "123", "--en"})
    @DisplayName("an unsupported or malformed tag falls back rather than throwing")
    void unsupportedFallsBack(String requested) {
        assertThat(AppLocales.normalize(requested)).isEqualTo(AppLocales.DEFAULT);
    }

    @Test
    @DisplayName("a malformed tag whose language subtag is unambiguous still resolves")
    void malformedButUnambiguous() {
        // "en-" is not a valid tag, but nothing about which language it means is
        // in doubt. The read path is forgiving on purpose, so it resolves rather
        // than falling back. isSupported() is what refuses to STORE it.
        assertThat(AppLocales.normalize("en-")).isEqualTo("en");
        assertThat(AppLocales.isSupported("en-")).isFalse();
    }

    @Test
    @DisplayName("null and blank fall back to the default")
    void nullAndBlankFallBack() {
        assertThat(AppLocales.normalize(null)).isEqualTo(AppLocales.DEFAULT);
        assertThat(AppLocales.normalize("")).isEqualTo(AppLocales.DEFAULT);
        assertThat(AppLocales.normalize("   ")).isEqualTo(AppLocales.DEFAULT);
    }

    // ─── the write path is stricter than the read path ──────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"sr-Latn", "SR-LATN", "sr_Latn", "en", "EN", " en "})
    @DisplayName("isSupported accepts a shipped locale regardless of casing or separator")
    void isSupportedAcceptsShipped(String code) {
        assertThat(AppLocales.isSupported(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "sr", "sr-Cyrl", "ru", "de", "", "   "})
    @DisplayName("isSupported refuses anything that is not a shipped locale exactly")
    void isSupportedRefusesTheRest(String code) {
        // en-US and sr are deliberately here: they NORMALISE to something supported
        // but are not themselves supported. Storing them would give the column a
        // second spelling of a locale it already has.
        assertThat(AppLocales.isSupported(code)).isFalse();
    }

    @Test
    @DisplayName("isSupported refuses null")
    void isSupportedRefusesNull() {
        assertThat(AppLocales.isSupported(null)).isFalse();
    }

    @Test
    @DisplayName("what isSupported accepts, normalize turns into the canonical spelling")
    void acceptedInputNormalisesToCanonical() {
        // This pairing is the contract EmployeeService relies on: validate with
        // isSupported, store normalize(). Anything accepted must land on a value
        // the CHECK constraint allows.
        for (String candidate : new String[]{"SR-LATN", "sr_Latn", " en ", "EN"}) {
            assertThat(AppLocales.isSupported(candidate)).isTrue();
            assertThat(AppLocales.SUPPORTED).contains(AppLocales.normalize(candidate));
        }
    }

    // ─── isDefault ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("isDefault follows normalisation, so a regional or cased variant still counts")
    void isDefaultFollowsNormalisation() {
        assertThat(AppLocales.isDefault("sr-Latn")).isTrue();
        assertThat(AppLocales.isDefault("SR-LATN")).isTrue();
        assertThat(AppLocales.isDefault("sr-Latn-RS")).isTrue();
        assertThat(AppLocales.isDefault("sr")).isTrue();
        assertThat(AppLocales.isDefault(null)).isTrue();   // null renders in the default
        assertThat(AppLocales.isDefault("en")).isFalse();
    }
}
