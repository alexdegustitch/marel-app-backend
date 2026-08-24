package com.aleksandarparipovic.marel_app.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What counts as an acceptable password here.
 *
 * <p>Pure and static so it can be applied in three places without three opinions:
 * registration, an administrator setting somebody's password, and a person
 * changing their own. Before this, registration asked for four characters and the
 * other two paths asked for nothing at all.
 *
 * <p><b>Every rule returns a sentence, and all failing sentences come back at
 * once.</b> A form that refuses once per attempt — "too short", then "needs a
 * digit", then "needs a capital" — makes somebody guess their way to a password
 * three tries at a time.
 *
 * <p>The composition rules are deliberately modest. Length does far more for
 * strength than punctuation does, and a factory floor that is forced into
 * `Lozinka1!` writes it on the monitor. Eight characters with a lower-case
 * letter, a capital and a digit is the floor; anything longer is better and the
 * screen says so rather than demanding it.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    /**
     * An upper bound at all, because bcrypt silently ignores input past 72 bytes —
     * a 200-character password would be quietly truncated, and the part the person
     * typed after that would do nothing. Refusing is honest; truncating is not.
     */
    public static final int MAX_LENGTH = 72;

    private static final Pattern LOWERCASE = Pattern.compile("\\p{Ll}");
    private static final Pattern UPPERCASE = Pattern.compile("\\p{Lu}");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    /**
     * Shortest fragment of a username or e-mail worth refusing inside a password.
     * Below this it stops being "your name with a 1 after it" and starts refusing
     * passwords for containing the letters of a two-letter login.
     */
    private static final int MIN_IDENTITY_FRAGMENT = 4;

    private PasswordPolicy() {
    }

    /**
     * @param username may be null — an administrator setting a password before the
     *                 username exists, for instance
     * @param email    may be null; only the part before the @ is compared
     * @return every rule the password fails, in Serbian, or an empty list
     */
    public static List<String> violations(String password, String username, String email) {
        List<String> problems = new ArrayList<>();

        if (password == null || password.isEmpty()) {
            problems.add("Lozinka je obavezna.");
            return problems;
        }

        if (password.length() < MIN_LENGTH) {
            problems.add("Lozinka mora imati najmanje " + MIN_LENGTH + " karaktera.");
        }
        if (password.length() > MAX_LENGTH) {
            problems.add("Lozinka može imati najviše " + MAX_LENGTH + " karaktera.");
        }
        if (!LOWERCASE.matcher(password).find()) {
            problems.add("Lozinka mora sadržati bar jedno malo slovo.");
        }
        if (!UPPERCASE.matcher(password).find()) {
            problems.add("Lozinka mora sadržati bar jedno veliko slovo.");
        }
        if (!DIGIT.matcher(password).find()) {
            problems.add("Lozinka mora sadržati bar jednu cifru.");
        }
        if (WHITESPACE.matcher(password).find()) {
            // Not prudishness: a password with a space in it is pasted wrongly,
            // trimmed by one client and not another, and typed differently on a
            // phone keyboard. It fails in ways nobody can see.
            problems.add("Lozinka ne sme sadržati razmak.");
        }

        String lower = password.toLowerCase(Locale.ROOT);
        if (containsIdentity(lower, username)) {
            problems.add("Lozinka ne sme sadržati korisničko ime.");
        }
        if (containsIdentity(lower, localPartOf(email))) {
            problems.add("Lozinka ne sme sadržati vašu e-adresu.");
        }

        return problems;
    }

    /** True when the password fails nothing. */
    public static boolean isAcceptable(String password, String username, String email) {
        return violations(password, username, email).isEmpty();
    }

    private static boolean containsIdentity(String lowerPassword, String identity) {
        if (identity == null) return false;
        String needle = identity.trim().toLowerCase(Locale.ROOT);
        return needle.length() >= MIN_IDENTITY_FRAGMENT && lowerPassword.contains(needle);
    }

    private static String localPartOf(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
