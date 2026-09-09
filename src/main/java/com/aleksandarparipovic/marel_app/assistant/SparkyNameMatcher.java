package com.aleksandarparipovic.marel_app.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Diacritic-folding, partial-token name matching for Sparky's resolvers.
 *
 * <p>The database side narrows candidates with a {@code translate()}-folded,
 * any-token {@code LIKE} (see the {@code searchFolded} finders); this class does
 * the same folding in Java and ranks the returned candidates so a fuzzy user
 * phrase ("kuciste pumpa") lands on the right row ("Kućište pumpe").
 *
 * <p>Folding = Unicode NFD decomposition with the combining marks stripped, plus
 * the stroke letters NFD does not decompose ({@code đ/Đ}), lower-cased. This is
 * pure and Spring-free so it can be unit-tested on its own.
 */
public final class SparkyNameMatcher {

    private SparkyNameMatcher() {
    }

    /** A resolved-name candidate. {@code code} and {@code productId} may be null. */
    public record Candidate(Long id, String name, String code, Long productId) {
    }

    /** Fold diacritics and case: "Kućište" → "kuciste", "pumpe" → "pumpe". */
    public static String fold(String s) {
        if (s == null) {
            return "";
        }
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        // NFD does not decompose the stroke letters; map them explicitly.
        stripped = stripped.replace('đ', 'd').replace('Đ', 'D');
        return stripped.toLowerCase().trim();
    }

    /** Split an already-folded string into non-empty whitespace tokens. */
    public static List<String> tokens(String folded) {
        List<String> out = new ArrayList<>();
        if (folded == null || folded.isBlank()) {
            return out;
        }
        for (String t : folded.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static int matchedTokens(String foldedName, String foldedCode, List<String> queryTokens) {
        int matched = 0;
        for (String t : queryTokens) {
            if (foldedName.contains(t) || (!foldedCode.isEmpty() && foldedCode.contains(t))) {
                matched++;
            }
        }
        return matched;
    }

    /**
     * Rank candidates best-first for {@code query}, scoring by (a) number of
     * matched folded tokens, then (b) folded name starts-with the folded query,
     * then (c) exact folded-code match, then (d) shorter name.
     */
    public static List<Candidate> rank(List<Candidate> candidates, String query) {
        String fq = fold(query);
        List<String> queryTokens = tokens(fq);

        record Scored(Candidate candidate, int matched, int startsWith, int exactCode, int nameLen) {
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            String fn = fold(c.name());
            String fc = fold(c.code());
            int matched = matchedTokens(fn, fc, queryTokens);
            int startsWith = !fq.isEmpty() && fn.startsWith(fq) ? 1 : 0;
            int exactCode = !fq.isEmpty() && fc.equals(fq) ? 1 : 0;
            scored.add(new Scored(c, matched, startsWith, exactCode, fn.length()));
        }

        scored.sort(Comparator
                .comparingInt(Scored::matched).reversed()
                .thenComparing(Comparator.comparingInt(Scored::startsWith).reversed())
                .thenComparing(Comparator.comparingInt(Scored::exactCode).reversed())
                .thenComparingInt(Scored::nameLen));

        return scored.stream().map(Scored::candidate).toList();
    }

    /** The single best candidate for {@code query}, or null when there are none. */
    public static Candidate best(List<Candidate> candidates, String query) {
        List<Candidate> ranked = rank(candidates, query);
        return ranked.isEmpty() ? null : ranked.get(0);
    }
}
