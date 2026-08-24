package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.account.UsernameRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a username may be, and what one is suggested to be.
 *
 * <p>The suggestion rule the owner asked for: the part of the e-mail address
 * before the @. It is what the person already thinks of as their name, and it is
 * their only chance to choose — a username is frozen once the account exists.
 */
class UsernameRulesTest {

    @Test
    @DisplayName("the suggestion is the part before the @")
    void suggestsTheLocalPart() {
        assertThat(UsernameRules.suggestFrom("dijana.rad@gmail.com", "Dijana", "Radivojević"))
                .isEqualTo("dijana.rad");
        assertThat(UsernameRules.suggestFrom("petar@marel.rs", "Petar", "Petrović"))
                .isEqualTo("petar");
    }

    @Test
    @DisplayName("the dot in the address survives — it is part of the name")
    void keepsSeparators() {
        // slugify() strips dots because it builds "first.last" and adds its own.
        // Applied here it would turn dijana.rad into dijanarad, which is not the
        // name anybody wrote down.
        assertThat(UsernameRules.suggestFrom("ana-marija_k@firma.rs", "Ana", "Marija"))
                .isEqualTo("ana-marija_k");
    }

    @Test
    @DisplayName("Serbian letters are transliterated the way the rest of the app does")
    void transliterates() {
        assertThat(UsernameRules.suggestFrom("randjelović@marel.rs", "Mila", "Ranđelović"))
                .isEqualTo("randjelovic");
        assertThat(UsernameRules.suggestFrom("šećer.žito@marel.rs", "A", "B"))
                .isEqualTo("secer.zito");
    }

    @Test
    @DisplayName("an address with nothing usable in it falls back to the name")
    void fallsBackToTheName() {
        assertThat(UsernameRules.suggestFrom("...@marel.rs", "Dijana", "Radivojević"))
                .isEqualTo("dijana.radivojevic");
        assertThat(UsernameRules.suggestFrom(null, "Dijana", "Radivojević"))
                .isEqualTo("dijana.radivojevic");
    }

    @Test
    @DisplayName("and with no name either, something valid still comes back")
    void neverReturnsSomethingInvalid() {
        String suggestion = UsernameRules.suggestFrom(null, null, null);

        assertThat(UsernameRules.isValid(suggestion)).isTrue();
    }

    @Test
    @DisplayName("what a typed username may look like")
    void validation() {
        assertThat(UsernameRules.isValid("dijana.rad")).isTrue();
        assertThat(UsernameRules.isValid("petar")).isTrue();
        assertThat(UsernameRules.isValid("ana-marija_k")).isTrue();
        assertThat(UsernameRules.isValid("a1b2")).isTrue();
    }

    /*
     * The ends and the doubling are what make two visually identical usernames,
     * and a username that starts with punctuation reads and sorts badly wherever
     * it appears.
     */
    @Test
    @DisplayName("separators may not sit at either end or come twice in a row")
    void refusesBadSeparatorPlacement() {
        assertThat(UsernameRules.isValid(".dijana")).isFalse();
        assertThat(UsernameRules.isValid("dijana.")).isFalse();
        assertThat(UsernameRules.isValid("dijana..rad")).isFalse();
        assertThat(UsernameRules.isValid("_dijana_")).isFalse();
    }

    @Test
    @DisplayName("no capitals, spaces or anything exotic")
    void refusesEverythingElse() {
        assertThat(UsernameRules.isValid("Dijana")).isFalse();
        assertThat(UsernameRules.isValid("dijana rad")).isFalse();
        assertThat(UsernameRules.isValid("dijana@marel.rs")).isFalse();
        assertThat(UsernameRules.isValid("đorđe")).isFalse();
    }

    @Test
    @DisplayName("length has both ends")
    void length() {
        assertThat(UsernameRules.isValid("ab")).isFalse();
        assertThat(UsernameRules.isValid("abc")).isTrue();
        assertThat(UsernameRules.isValid("a".repeat(32))).isTrue();
        assertThat(UsernameRules.isValid("a".repeat(33))).isFalse();
    }

    @Test
    @DisplayName("null and blank are not usernames")
    void nothing() {
        assertThat(UsernameRules.isValid(null)).isFalse();
        assertThat(UsernameRules.isValid("")).isFalse();
        assertThat(UsernameRules.isValid("   ")).isFalse();
    }

    /*
     * The property that matters more than any single case above: whatever the
     * address looks like, the derived username is one the rules would accept.
     * Otherwise registration suggests a name and then refuses it.
     */
    @Test
    @DisplayName("the suggestion is always something the rules accept")
    void suggestionsAreAlwaysValid() {
        String[] addresses = {
                "dijana.rad@gmail.com", "..weird..@x.rs", "a@b.rs", "ĐŽŠ@marel.rs",
                "very.long.address.that.goes.on.and.on.and.on.for.ever@marel.rs",
        };

        for (String address : addresses) {
            String suggestion = UsernameRules.suggestFrom(address, "Ime", "Prezime");
            assertThat(UsernameRules.isValid(suggestion))
                    .as("suggestion for %s was '%s'", address, suggestion)
                    .isTrue();
        }
    }
}
