package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.account.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as an acceptable password.
 *
 * <p>These rules are applied in three places — registration, an administrator's
 * reset, and a person changing their own — and the point of the class is that all
 * three get the same answer. Before it, registration asked for four characters
 * and the other two asked for nothing.
 */
class PasswordPolicyTest {

    @Test
    @DisplayName("an ordinary good password passes")
    void acceptsAReasonablePassword() {
        assertThat(PasswordPolicy.violations("Fabrika2026", "dijana.rad", "dijana.rad@gmail.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("every failing rule is reported at once, not one per attempt")
    void reportsEveryProblemTogether() {
        // A form that refuses once per try — "too short", then "needs a digit" —
        // makes somebody guess their way to a password three attempts at a time.
        assertThat(PasswordPolicy.violations("abc", null, null))
                .hasSizeGreaterThan(2)
                .anyMatch(problem -> problem.contains("najmanje"))
                .anyMatch(problem -> problem.contains("veliko slovo"))
                .anyMatch(problem -> problem.contains("cifru"));
    }

    @Test
    @DisplayName("the composition rules")
    void composition() {
        assertThat(PasswordPolicy.violations("fabrika2026", null, null))
                .containsExactly("Lozinka mora sadržati bar jedno veliko slovo.");
        assertThat(PasswordPolicy.violations("FABRIKA2026", null, null))
                .containsExactly("Lozinka mora sadržati bar jedno malo slovo.");
        assertThat(PasswordPolicy.violations("FabrikaFabrika", null, null))
                .containsExactly("Lozinka mora sadržati bar jednu cifru.");
    }

    @Test
    @DisplayName("Serbian letters count as letters")
    void serbianLettersAreLetters() {
        // \\p{Ll} and \\p{Lu}, not [a-z] and [A-Z]. With ASCII classes "Šećer123"
        // would be refused for having no capital, which is nonsense to the person
        // who typed one.
        assertThat(PasswordPolicy.violations("Šećerana2026", null, null)).isEmpty();
        assertThat(PasswordPolicy.violations("ŠEĆERANA2026", null, null))
                .containsExactly("Lozinka mora sadržati bar jedno malo slovo.");
    }

    /*
     * A space in a password fails in ways nobody can see: pasted with a trailing
     * one, trimmed by one client and not another, typed differently on a phone.
     */
    @Test
    @DisplayName("no whitespace anywhere")
    void refusesWhitespace() {
        assertThat(PasswordPolicy.violations("Fabrika 2026", null, null))
                .contains("Lozinka ne sme sadržati razmak.");
    }

    @Test
    @DisplayName("a password may not carry the username or the e-mail in it")
    void refusesIdentityInside() {
        assertThat(PasswordPolicy.violations("Dijana.rad1", "dijana.rad", null))
                .contains("Lozinka ne sme sadržati korisničko ime.");

        // Only the part before the @ — nobody's password is refused for
        // containing "gmail".
        assertThat(PasswordPolicy.violations("Petar.petrovic9", null, "petar.petrovic@marel.rs"))
                .contains("Lozinka ne sme sadržati vašu e-adresu.");
        assertThat(PasswordPolicy.violations("Gmail12345", null, "ana@gmail.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("a very short username is not grounds to refuse a password")
    void shortIdentitiesAreNotMatched() {
        // "ab" appears in half of all passwords. Refusing for it would be a rule
        // nobody could satisfy or understand.
        assertThat(PasswordPolicy.violations("Fabrika2026", "ab", null)).isEmpty();
    }

    /*
     * bcrypt ignores input past 72 bytes. Accepting a 200-character password would
     * silently use the first 72 and quietly discard what the person typed after —
     * refusing is honest, truncating is not.
     */
    @Test
    @DisplayName("refuses what bcrypt would silently truncate")
    void refusesPastTheHashLimit() {
        String tooLong = "Fabrika2026".repeat(10);

        assertThat(tooLong.length()).isGreaterThan(PasswordPolicy.MAX_LENGTH);
        assertThat(PasswordPolicy.violations(tooLong, null, null))
                .contains("Lozinka može imati najviše " + PasswordPolicy.MAX_LENGTH + " karaktera.");
    }

    @Test
    @DisplayName("nothing at all is one clear refusal, not a list")
    void missingPassword() {
        assertThat(PasswordPolicy.violations(null, null, null))
                .containsExactly("Lozinka je obavezna.");
        assertThat(PasswordPolicy.violations("", null, null))
                .containsExactly("Lozinka je obavezna.");
    }

    @Test
    @DisplayName("exactly at the minimum is acceptable")
    void boundary() {
        assertThat(PasswordPolicy.MIN_LENGTH).isEqualTo(8);
        assertThat(PasswordPolicy.isAcceptable("Fabrik1a", null, null)).isTrue();
        assertThat(PasswordPolicy.isAcceptable("Fabri1a", null, null)).isFalse();
    }
}
