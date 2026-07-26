package com.aleksandarparipovic.marel_app.user;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a name into the "firstname.lastname" ASCII username style already used by
 * existing accounts (e.g. "Ranđelović" -> "randjelovic"), transliterating Serbian
 * Latin diacritics before stripping anything else non-alphanumeric.
 */
public final class UsernameGenerator {

    private UsernameGenerator() {
    }

    public static String slugify(String value) {
        if (value == null) {
            return "";
        }

        String transliterated = value
                .replace("đ", "dj").replace("Đ", "Dj")
                .replace("č", "c").replace("Č", "C")
                .replace("ć", "c").replace("Ć", "C")
                .replace("š", "s").replace("Š", "S")
                .replace("ž", "z").replace("Ž", "Z");

        String normalized = Normalizer.normalize(transliterated, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static String baseUsername(String firstName, String lastName) {
        String first = slugify(firstName);
        String last = slugify(lastName);

        if (first.isBlank() && last.isBlank()) {
            return "user";
        }
        if (last.isBlank()) {
            return first;
        }
        if (first.isBlank()) {
            return last;
        }
        return first + "." + last;
    }
}
