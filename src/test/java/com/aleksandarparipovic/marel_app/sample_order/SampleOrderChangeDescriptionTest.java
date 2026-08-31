package com.aleksandarparipovic.marel_app.sample_order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a save of a sample order actually tells the recipients.
 *
 * <p>Two failure modes, both silent and both bad. Report a change that did not
 * happen and every corrected typo mails the whole recipient list — update()
 * rewrites the line items on every save whether the form touched them or not, so
 * this comparison is the only thing standing between a note fix and an inbox.
 * Miss a change that did happen and the shop floor works to a rok nobody told
 * them moved.
 */
class SampleOrderChangeDescriptionTest {

    private static SampleOrderService.OrderSnapshot snapshot(
            String name, String note, LocalDate deadline, String deadlineNote, List<String> lineItems
    ) {
        return new SampleOrderService.OrderSnapshot(
                name, note, "Metalac",
                LocalDate.of(2026, 8, 1), deadline, deadlineNote, lineItems);
    }

    private static SampleOrderService.OrderSnapshot unchanged() {
        return snapshot("Uzorci kućišta", "za sajam", LocalDate.of(2026, 9, 10),
                "po dogovoru", List.of("Nosač (5 kom)"));
    }

    @Test
    @DisplayName("an identical save announces nothing")
    void identicalSaveIsSilent() {
        assertThat(SampleOrderService.describeChanges(unchanged(), unchanged())).isEmpty();
    }

    @Test
    @DisplayName("a moved rok is named with both dates, in the order people read them")
    void deadlineChangeNamesBothValues() {
        var after = snapshot("Uzorci kućišta", "za sajam", LocalDate.of(2026, 9, 8),
                "po dogovoru", List.of("Nosač (5 kom)"));

        assertThat(SampleOrderService.describeChanges(unchanged(), after))
                .containsExactly("rok: 10.09.2026. → 08.09.2026.");
    }

    @Test
    @DisplayName("the rok in words moves on its own, without the date having moved")
    void deadlineNoteChangesAlone() {
        var after = snapshot("Uzorci kućišta", "za sajam", LocalDate.of(2026, 9, 10),
                "najkasnije do sajma", List.of("Nosač (5 kom)"));

        assertThat(SampleOrderService.describeChanges(unchanged(), after))
                .containsExactly("napomena uz rok: po dogovoru → najkasnije do sajma");
    }

    @Test
    @DisplayName("a changed quantity is a changed line, and says so")
    void quantityChangeIsALineChange() {
        var after = snapshot("Uzorci kućišta", "za sajam", LocalDate.of(2026, 9, 10),
                "po dogovoru", List.of("Nosač (8 kom)"));

        assertThat(SampleOrderService.describeChanges(unchanged(), after))
                .containsExactly("stavke: Nosač (5 kom) → Nosač (8 kom)");
    }

    @Test
    @DisplayName("reordering the same products is a change, because the floor works through them in order")
    void reorderingIsAChange() {
        var before = snapshot("Uzorci kućišta", "za sajam", LocalDate.of(2026, 9, 10),
                "po dogovoru", List.of("Nosač (5 kom)", "Poklopac (2 kom)"));
        var after = snapshot("Uzorci kućišta", "za sajam", LocalDate.of(2026, 9, 10),
                "po dogovoru", List.of("Poklopac (2 kom)", "Nosač (5 kom)"));

        assertThat(SampleOrderService.describeChanges(before, after))
                .containsExactly(
                        "stavke: Nosač (5 kom), Poklopac (2 kom) → Poklopac (2 kom), Nosač (5 kom)");
    }

    @Test
    @DisplayName("one save that changes three things produces three entries, not three mails")
    void severalChangesInOneSave() {
        var after = snapshot("Uzorci poklopca", "za sajam u Novom Sadu",
                LocalDate.of(2026, 9, 10), "po dogovoru", List.of("Nosač (5 kom)"));

        assertThat(SampleOrderService.describeChanges(unchanged(), after))
                .containsExactly(
                        "naziv: Uzorci kućišta → Uzorci poklopca",
                        "napomena: za sajam → za sajam u Novom Sadu");
    }

    @Test
    @DisplayName("a value that was never set reads as a sentence, not as null")
    void absentValuesReadAsWords() {
        var before = snapshot("Uzorci kućišta", null, LocalDate.of(2026, 9, 10),
                null, List.of());
        var after = snapshot("Uzorci kućišta", "za sajam", LocalDate.of(2026, 9, 10),
                "po dogovoru", List.of("Nosač (5 kom)"));

        assertThat(SampleOrderService.describeChanges(before, after))
                .containsExactly(
                        "napomena: nije postavljeno → za sajam",
                        "napomena uz rok: nije postavljeno → po dogovoru",
                        "stavke: nije postavljeno → Nosač (5 kom)");
    }
}
