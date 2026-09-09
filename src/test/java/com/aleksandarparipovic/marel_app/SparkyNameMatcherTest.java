package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.assistant.SparkyNameMatcher;
import com.aleksandarparipovic.marel_app.assistant.SparkyNameMatcher.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diacritic folding and candidate ranking for Sparky's fuzzy name resolution.
 * A pure unit test — no Spring context, no database — mirroring the Java half of
 * the resolver that the DB {@code searchFolded} finders feed.
 */
class SparkyNameMatcherTest {

    @Test
    @DisplayName("fold() strips diacritics and stroke letters, lower-cases")
    void foldsDiacritics() {
        assertThat(SparkyNameMatcher.fold("Kućište")).isEqualTo("kuciste");
        assertThat(SparkyNameMatcher.fold("Đorđe ŠŽčć")).isEqualTo("dorde szcc");
        assertThat(SparkyNameMatcher.fold(null)).isEmpty();
    }

    @Test
    @DisplayName("tokens() splits a folded phrase into non-empty tokens")
    void splitsTokens() {
        assertThat(SparkyNameMatcher.tokens("kuciste  pumpa ")).containsExactly("kuciste", "pumpa");
        assertThat(SparkyNameMatcher.tokens("")).isEmpty();
    }

    @Test
    @DisplayName("best() resolves a fuzzy, diacritic-free, wrong-case query to the real product")
    void resolvesFuzzyProduct() {
        List<Candidate> candidates = List.of(
                new Candidate(1L, "Kućište pumpe", "KP-100", null),
                new Candidate(2L, "Poklopac kućišta", "PK-200", null),
                new Candidate(3L, "Pumpa hidraulična", "PH-300", null));

        // "kuciste pumpa": no diacritics, "pumpa" is a different case form than "pumpe".
        Candidate best = SparkyNameMatcher.best(candidates, "kuciste pumpa");
        assertThat(best).isNotNull();
        assertThat(best.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ranking prefers more matched tokens, then starts-with, then shorter name")
    void ranksByTokensThenLength() {
        List<Candidate> ops = List.of(
                new Candidate(30L, "Operacija 30", null, 1L),
                new Candidate(3L, "Operacija 3", null, 1L),
                new Candidate(99L, "Bušenje", null, 1L));

        List<Candidate> ranked = SparkyNameMatcher.rank(ops, "operacija 3");
        // Both "Operacija 3" and "Operacija 30" match both tokens and start with the
        // query; the shorter name wins the tie.
        assertThat(ranked.get(0).id()).isEqualTo(3L);
        assertThat(ranked.get(ranked.size() - 1).id()).isEqualTo(99L);
    }

    @Test
    @DisplayName("exact folded-code match outranks a mere name contains")
    void exactCodeWins() {
        List<Candidate> candidates = List.of(
                new Candidate(1L, "Neki proizvod sa kp u imenu", "X-1", null),
                new Candidate(2L, "Drugi proizvod", "KP", null));

        Candidate best = SparkyNameMatcher.best(candidates, "kp");
        assertThat(best.id()).isEqualTo(2L);
    }
}
