package com.aleksandarparipovic.marel_app.production_order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a save actually tells the recipients.
 *
 * <p>Two failure modes, both silent and both bad. Report a change that did not
 * happen and every corrected typo mails the whole recipient list — update()
 * rewrites deadlines and line items on every save whether the form touched them
 * or not, so this comparison is the only thing standing between a note fix and
 * an inbox. Miss a change that did happen and the shop floor works to a deadline
 * nobody told them moved.
 */
class ProductionOrderChangeDescriptionTest {

    private static ProductionOrderService.OrderSnapshot snapshot(
            String name, String note, String deliveryDeadline,
            Boolean priority, List<String> deadlines, List<String> lineItems
    ) {
        return new ProductionOrderService.OrderSnapshot(
                name, note, "Metalac",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2),
                deliveryDeadline, false, priority, false, false,
                deadlines, lineItems);
    }

    private static ProductionOrderService.OrderSnapshot unchanged() {
        return snapshot("Kućišta", "prva serija", "10.09.2026.", false,
                List.of("10.09.2026. (100 kom)"), List.of("Nosač (100 kom)"));
    }

    @Test
    @DisplayName("an identical save announces nothing")
    void identicalSaveIsSilent() {
        assertThat(ProductionOrderService.describeChanges(unchanged(), unchanged()))
                .isEmpty();
    }

    @Test
    @DisplayName("a moved deadline is named with both values")
    void deadlineChangeNamesBothValues() {
        var after = snapshot("Kućišta", "prva serija", "08.09.2026.", false,
                List.of("08.09.2026. (100 kom)"), List.of("Nosač (100 kom)"));

        assertThat(ProductionOrderService.describeChanges(unchanged(), after))
                .containsExactly(
                        "rok isporuke: 10.09.2026. → 08.09.2026.",
                        "rokovi: 10.09.2026. (100 kom) → 08.09.2026. (100 kom)");
    }

    @Test
    @DisplayName("one save that changes three things produces three entries, not three mails")
    void severalChangesInOneSave() {
        var after = snapshot("Kućišta v2", "druga serija", "10.09.2026.", true,
                List.of("10.09.2026. (100 kom)"), List.of("Nosač (120 kom)"));

        assertThat(ProductionOrderService.describeChanges(unchanged(), after))
                .containsExactly(
                        "naziv: Kućišta → Kućišta v2",
                        "napomena: prva serija → druga serija",
                        "prioritet: ne → da",
                        "stavke: Nosač (100 kom) → Nosač (120 kom)");
    }

    @Test
    @DisplayName("booleans and empty values read as words, not as toString")
    void valuesReadAsSerbian() {
        var before = snapshot("Kućišta", null, "10.09.2026.", false,
                List.of(), List.of("Nosač (100 kom)"));
        var after = snapshot("Kućišta", "dodata", "10.09.2026.", true,
                List.of("10.09.2026."), List.of("Nosač (100 kom)"));

        assertThat(ProductionOrderService.describeChanges(before, after))
                .containsExactly(
                        "napomena: nije postavljeno → dodata",
                        "prioritet: ne → da",
                        "rokovi: nije postavljeno → 10.09.2026.");
    }

    @Test
    @DisplayName("reordering the same line items counts as a change")
    void reorderingLinesIsAChange() {
        var before = snapshot("Kućišta", "prva serija", "10.09.2026.", false,
                List.of("10.09.2026. (100 kom)"), List.of("Nosač (10 kom)", "Osovina (5 kom)"));
        var after = snapshot("Kućišta", "prva serija", "10.09.2026.", false,
                List.of("10.09.2026. (100 kom)"), List.of("Osovina (5 kom)", "Nosač (10 kom)"));

        // The order of lines is what the shop floor works through, so a swap is
        // a real change even though the set is identical.
        assertThat(ProductionOrderService.describeChanges(before, after)).hasSize(1);
    }
}
