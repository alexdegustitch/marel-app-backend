package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.user.UsernameGenerator;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a username may be, and what one is suggested to be.
 *
 * <p><b>A username is chosen once and never again.</b> It is what somebody signs
 * in with, what appears beside their name everywhere in this application, and
 * what the audit trail says did a thing. Letting it move means the record of who
 * did what stops resolving — and there is no second identifier on those rows to
 * fall back to. So it is offered at registration, defaulted sensibly, and frozen
 * the moment the account exists. Not even an administrator may change it.
 *
 * <p>The DEFAULT is the part of the e-mail address before the @, because that is
 * the name the person already thinks of as theirs: `dijana.rad@gmail.com`
 * suggests `dijana.rad`. It is only a suggestion — the registration form offers
 * it filled in and lets them type something else.
 */
public final class UsernameRules {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 32;

    /**
     * Lower-case letters and digits, with a dot, underscore or hyphen allowed
     * BETWEEN them — never at either end and never twice in a row.
     *
     * <p>The ends and the doubling matter more than they look: `..` and a trailing
     * dot are what make two visually identical usernames, and a username that
     * starts with punctuation sorts and reads badly everywhere it appears.
     */
    private static final Pattern VALID =
            Pattern.compile("^[a-z0-9]+(?:[._-][a-z0-9]+)*$");

    private UsernameRules() {
    }

    /**
     * The username to offer for an e-mail address.
     *
     * <p>Transliterates the way the rest of this application does (Ranđelović →
     * randjelovic), keeps the separators an address may legitimately contain, and
     * drops everything else. Falls back to a name-derived username when the local
     * part cannot survive that — an address that is entirely punctuation, or
     * missing altogether.
     */
    public static String suggestFrom(String email, String firstName, String lastName) {
        String suggestion = normalise(localPartOf(email));

        if (isValid(suggestion)) {
            return suggestion;
        }

        // Nothing usable in the address; fall back to the "ime.prezime" style the
        // existing accounts in this database already use.
        String fromName = UsernameGenerator.baseUsername(firstName, lastName);
        return isValid(fromName) ? fromName : "korisnik";
    }

    /**
     * Tidies a candidate into the shape the rules accept, as far as that is
     * possible without inventing characters.
     *
     * <p>Deliberately NOT applied to what a person typed into the form: silently
     * turning `Dijana Rad` into `dijana.rad` and saving it means they signed in
     * for the first time with a username they never saw. It is used for the
     * SUGGESTION only; a typed username is validated and refused, not corrected.
     */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }

        String transliterated = UsernameGenerator.slugifyKeepingSeparators(
                value.trim().toLowerCase(Locale.ROOT));

        // Collapse runs of separators and trim them off both ends.
        String collapsed = transliterated
                .replaceAll("[._-]{2,}", ".")
                .replaceAll("^[._-]+", "")
                .replaceAll("[._-]+$", "");

        return collapsed.length() > MAX_LENGTH ? collapsed.substring(0, MAX_LENGTH) : collapsed;
    }

    public static boolean isValid(String username) {
        if (username == null) return false;
        String value = username.trim();
        return value.length() >= MIN_LENGTH
                && value.length() <= MAX_LENGTH
                && VALID.matcher(value).matches();
    }

    /** The refusal a person reads. One sentence, saying what IS allowed. */
    public static String requirement() {
        return "Korisničko ime može imati " + MIN_LENGTH + "–" + MAX_LENGTH
                + " znakova: mala slova, cifre, i tačku, crtu ili donju crtu između njih.";
    }

    private static String localPartOf(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
