package com.aleksandarparipovic.marel_app.order_email_view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diff IS the mail's promise: what was removed reads struck through, what
 * was added reads bold, what nobody touched reads plain. These tests pin the
 * classification; the renderer's tests pin the markup.
 */
class OrderEmailViewTest {

    private static OrderEmailState state(
            List<OrderEmailState.Field> fields,
            List<String> deadlines,
            List<OrderEmailState.Item> items) {
        return new OrderEmailState(fields, deadlines, items);
    }

    private static OrderEmailState.Field field(String label, String value) {
        return new OrderEmailState.Field(label, value);
    }

    private static OrderEmailState.Item item(String key, String name, String qty, String note) {
        return new OrderEmailState.Item(key, name, qty, note);
    }

    @Test
    @DisplayName("a created order carries no marks at all")
    void createdOrderIsAllPlain() {
        OrderEmailView view = OrderEmailView.of("190/2026", state(
                List.of(field("Naziv", "ENIA Grčka")),
                List.of("03.09.2026. (500 kom)"),
                List.of(item("7", "Čaura", "500", null))));

        assertThat(view.fields()).allSatisfy(f -> assertThat(f.cell().changed()).isFalse());
        assertThat(view.deadlines()).allSatisfy(l ->
                assertThat(l.status()).isEqualTo(OrderEmailView.SAME));
        assertThat(view.items()).allSatisfy(i ->
                assertThat(i.status()).isEqualTo(OrderEmailView.SAME));
    }

    @Test
    @DisplayName("an edited field keeps its old value beside the new one")
    void editedFieldCarriesBothValues() {
        OrderEmailView view = OrderEmailView.diff("190/2026",
                state(List.of(field("Kupac", "ENIA")), List.of(), List.of()),
                state(List.of(field("Kupac", "ENIA Grčka")), List.of(), List.of()));

        OrderEmailView.Cell cell = view.fields().get(0).cell();
        assertThat(cell.changed()).isTrue();
        assertThat(cell.was()).isEqualTo("ENIA");
        assertThat(cell.value()).isEqualTo("ENIA Grčka");
    }

    @Test
    @DisplayName("a cleared flag survives as only its struck-through past")
    void clearedFlagKeepsOnlyThePast() {
        OrderEmailView view = OrderEmailView.diff("190/2026",
                state(List.of(field("Visok prioritet", "da")), List.of(), List.of()),
                state(List.of(field("Visok prioritet", null)), List.of(), List.of()));

        OrderEmailView.Cell cell = view.fields().get(0).cell();
        assertThat(cell.changed()).isTrue();
        assertThat(cell.was()).isEqualTo("da");
        assertThat(cell.value()).isNull();
    }

    @Test
    @DisplayName("items classify as added, removed, or changed by product — never by row identity")
    void itemsClassifyByProduct() {
        OrderEmailView view = OrderEmailView.diff("190/2026",
                state(List.of(), List.of(), List.of(
                        item("1", "Čaura 150", "500", null),
                        item("2", "Čaura 240", "50", null))),
                state(List.of(), List.of(), List.of(
                        item("1", "Čaura 150", "500", null),
                        item("2", "Čaura 240", "50 + 50", null),
                        item("3", "Univerzalna čaura", "200", "BMS 95-240"))));

        assertThat(view.items()).hasSize(3);
        assertThat(view.items().get(0).status()).isEqualTo(OrderEmailView.SAME);
        assertThat(view.items().get(0).quantity().changed()).isFalse();

        // The same product with a new quantity is a CHANGE to a known line.
        assertThat(view.items().get(1).status()).isEqualTo(OrderEmailView.SAME);
        assertThat(view.items().get(1).quantity().changed()).isTrue();
        assertThat(view.items().get(1).quantity().was()).isEqualTo("50");

        assertThat(view.items().get(2).status()).isEqualTo(OrderEmailView.ADDED);
    }

    @Test
    @DisplayName("a removed item stays in the view, marked, after every kept line")
    void removedItemStaysMarked() {
        OrderEmailView view = OrderEmailView.diff("190/2026",
                state(List.of(), List.of(), List.of(
                        item("1", "Čaura 150", "500", null),
                        item("2", "Čaura 240", "50", null))),
                state(List.of(), List.of(), List.of(
                        item("1", "Čaura 150", "500", null))));

        assertThat(view.items()).hasSize(2);
        assertThat(view.items().get(1).status()).isEqualTo(OrderEmailView.REMOVED);
        assertThat(view.items().get(1).name().value()).isEqualTo("Čaura 240");
    }

    @Test
    @DisplayName("deadlines match by value: moved means one removed and one added")
    void movedDeadlineIsRemovePlusAdd() {
        OrderEmailView view = OrderEmailView.diff("190/2026",
                state(List.of(), List.of("03.09.2026. (500 kom)"), List.of()),
                state(List.of(), List.of("10.09.2026. (500 kom)"), List.of()));

        assertThat(view.deadlines()).extracting(OrderEmailView.Line::status)
                .containsExactly(OrderEmailView.ADDED, OrderEmailView.REMOVED);
    }

    @Test
    @DisplayName("the same product on two lines claims one before-line each")
    void duplicateProductsPairOneToOne() {
        OrderEmailView view = OrderEmailView.diff("190/2026",
                state(List.of(), List.of(), List.of(
                        item("1", "Čaura", "100", null))),
                state(List.of(), List.of(), List.of(
                        item("1", "Čaura", "100", null),
                        item("1", "Čaura", "200", null))));

        assertThat(view.items().get(0).status()).isEqualTo(OrderEmailView.SAME);
        assertThat(view.items().get(0).quantity().changed()).isFalse();
        assertThat(view.items().get(1).status()).isEqualTo(OrderEmailView.ADDED);
    }
}
