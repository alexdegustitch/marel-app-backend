package com.aleksandarparipovic.marel_app.order_email_view;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The whole order as one mail renders it, with what this save changed marked
 * cell by cell.
 *
 * <p>Serialized into the outbox event's payload at PUBLISH time, inside the
 * business transaction — so the mail describes the order exactly as the save
 * left it, no matter when the delivery worker gets around to sending. The
 * renderer turns {@code was} values into strikethrough and new values into
 * bold, which is the format the recipients agreed on: obrisano precrtano,
 * dodato podebljano, ostalo običnim tekstom.
 *
 * <p>Value objects only — no entities, no repositories — so both sides
 * (publishing services and the delivery renderer) can be tested with literals.
 */
public record OrderEmailView(
        String code,
        List<Field> fields,
        List<Line> deadlines,
        List<Item> items
) {

    /** Row statuses the renderer understands. */
    public static final String SAME = "SAME";
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";

    /**
     * One displayable value and, when this save changed it, what it said before.
     *
     * <p>{@code changed} is carried explicitly rather than inferred from
     * {@code was != null}: a value that went from unset to set has no "was" but
     * must still render bold.
     */
    public record Cell(String value, String was, boolean changed) {

        public static Cell same(String value) {
            return new Cell(value, null, false);
        }

        public static Cell of(String before, String after) {
            return Objects.equals(before, after)
                    ? same(after)
                    : new Cell(after, before, true);
        }
    }

    /** One labelled header row — "Kupac: <del>ENIA</del> <b>ENIA Grčka</b>". */
    public record Field(String label, Cell cell) {
    }

    /** One successive-delivery deadline line, matched across saves by value. */
    public record Line(String status, String text) {
    }

    /** One line item row; a REMOVED row renders struck through in full. */
    public record Item(String status, Cell name, Cell quantity, Cell note) {
    }

    /** The order as it stands, nothing marked — for a created or terminal mail. */
    public static OrderEmailView of(String code, OrderEmailState state) {
        return diff(code, null, state);
    }

    /**
     * The order as this save left it, with every difference from {@code before}
     * marked. {@code before == null} marks nothing.
     *
     * <p>Fields pair by label. Deadlines match by value: a line present on both
     * sides is unchanged, one only before was removed, one only after was added
     * — no guessing about which removed line "became" which added one. Items
     * pair by their key (the product), so an edited quantity reads as a change
     * to the line the recipients already know rather than as a removal plus an
     * addition.
     */
    public static OrderEmailView diff(String code, OrderEmailState before, OrderEmailState after) {
        List<Field> fields = diffFields(before, after);
        List<Line> deadlines = diffDeadlines(before, after);
        List<Item> items = diffItems(before, after);
        return new OrderEmailView(code, fields, deadlines, items);
    }

    private static List<Field> diffFields(OrderEmailState before, OrderEmailState after) {
        Map<String, String> beforeByLabel = before == null
                ? Map.of()
                : before.fields().stream().collect(
                        java.util.stream.Collectors.toMap(
                                OrderEmailState.Field::label,
                                f -> f.value() == null ? "" : f.value()));

        List<Field> fields = new ArrayList<>();
        for (OrderEmailState.Field field : after.fields()) {
            Cell cell = before == null
                    ? Cell.same(field.value())
                    : Cell.of(
                            emptyToNull(beforeByLabel.get(field.label())),
                            field.value());
            fields.add(new Field(field.label(), cell));
        }
        return fields;
    }

    private static List<Line> diffDeadlines(OrderEmailState before, OrderEmailState after) {
        List<String> beforeLines = before == null
                ? List.of() : before.deadlines();
        List<String> afterLines = after.deadlines();

        List<Line> lines = new ArrayList<>();
        List<String> leftover = new LinkedList<>(beforeLines);
        for (String line : afterLines) {
            // Nothing to compare against on a created order: the lines are
            // simply the order, not additions to an empty one.
            if (before == null) {
                lines.add(new Line(SAME, line));
            } else if (leftover.remove(line)) {
                lines.add(new Line(SAME, line));
            } else {
                lines.add(new Line(ADDED, line));
            }
        }
        for (String removed : leftover) {
            lines.add(new Line(REMOVED, removed));
        }
        return lines;
    }

    private static List<Item> diffItems(OrderEmailState before, OrderEmailState after) {
        // A LinkedList of leftovers rather than a map: the same product can
        // legitimately appear on two lines, and each after-line should claim
        // one before-line at most.
        List<OrderEmailState.Item> leftover = new LinkedList<>(
                before == null ? List.of() : before.items());

        List<Item> items = new ArrayList<>();
        for (OrderEmailState.Item item : after.items()) {
            OrderEmailState.Item match = claim(leftover, item.key());

            if (before == null) {
                items.add(new Item(SAME,
                        Cell.same(item.name()),
                        Cell.same(item.quantity()),
                        Cell.same(item.note())));
            } else if (match == null) {
                items.add(new Item(ADDED,
                        Cell.same(item.name()),
                        Cell.same(item.quantity()),
                        Cell.same(item.note())));
            } else {
                items.add(new Item(SAME,
                        Cell.of(match.name(), item.name()),
                        Cell.of(match.quantity(), item.quantity()),
                        Cell.of(match.note(), item.note())));
            }
        }
        for (OrderEmailState.Item removed : leftover) {
            items.add(new Item(REMOVED,
                    Cell.same(removed.name()),
                    Cell.same(removed.quantity()),
                    Cell.same(removed.note())));
        }
        return items;
    }

    private static OrderEmailState.Item claim(List<OrderEmailState.Item> leftover, String key) {
        var it = leftover.iterator();
        while (it.hasNext()) {
            OrderEmailState.Item candidate = it.next();
            if (Objects.equals(candidate.key(), key)) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
