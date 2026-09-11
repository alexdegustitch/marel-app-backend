package com.aleksandarparipovic.marel_app.order_email_view;

import java.util.List;

/**
 * One side of an order, as it should READ in a mail — before or after a save.
 *
 * <p>Built by the order services, which already hold the loaded rows, and kept
 * as display strings rather than entities: the comparison that produces the
 * mail's strikethrough/bold markup is a comparison of what the recipient would
 * see, not of database identity. update() deactivates and re-inserts deadlines
 * and line items on every save, so by identity everything always "changed" —
 * by value, only a real edit does.
 *
 * @param fields    the order's header fields in display order, every label
 *                  present on both sides so the differ can pair them; a field
 *                  that is not set carries {@code null}
 * @param deadlines successive delivery deadlines as display lines ("03.09.2026.
 *                  (500 kom)"), compared by value
 * @param items     the line items in the order the shop floor works through
 */
public record OrderEmailState(
        List<Field> fields,
        List<String> deadlines,
        List<Item> items
) {

    /** One labelled header value — "Kupac: ENIA". Null value means not set. */
    public record Field(String label, String value) {
    }

    /**
     * One line item as displayed.
     *
     * @param key      what makes this "the same item" across two saves — the
     *                 product id, so a quantity edit reads as a change to the
     *                 line rather than a removal plus an addition
     * @param name     the product, with its description when one was written
     * @param quantity the quantities as one string ("500 + 50 kom"), partial
     *                 deadlines included
     * @param note     the line's own note; null when there is none
     */
    public record Item(String key, String name, String quantity, String note) {
    }
}
