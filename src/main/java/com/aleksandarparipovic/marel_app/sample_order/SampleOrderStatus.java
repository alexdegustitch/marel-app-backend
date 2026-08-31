package com.aleksandarparipovic.marel_app.sample_order;

import java.util.Locale;

/**
 * What state a sample order is in.
 *
 * <p><b>Constants, not an enum.</b> {@code sample_orders.status} is a plain
 * {@code varchar} whose default is the lower-case {@code 'created'}, and the
 * product page already reads and translates those values. Mapping the column as
 * a Java enum would start writing {@code 'CREATED'} beside rows that say
 * {@code 'created'} and quietly split one status into two — so the shape the
 * database actually holds is written down here instead of being converted.
 *
 * <p>Two states, because there are two things that have happened: somebody wrote
 * the order, and somebody finished it. There is no "in progress" — nothing in
 * this application observes a sample being made, so a third state would be a
 * value nobody could ever set truthfully.
 */
public final class SampleOrderStatus {

    /** Written, and still being worked. Every order starts here. */
    public static final String CREATED = "created";

    /** Finished and handed over. Terminal, like a production order's DELIVERED. */
    public static final String CLOSED = "closed";

    private SampleOrderStatus() {
    }

    /**
     * Whether the order is finished.
     *
     * <p>Compared case-insensitively: the column is free text, and a row written
     * by hand as {@code 'CLOSED'} means the same thing as one written by this
     * application. Reading it strictly would show such an order as still open —
     * a difference nobody could see on screen and nobody would think to look for.
     */
    public static boolean isClosed(String status) {
        return status != null && CLOSED.equalsIgnoreCase(status.trim());
    }

    /** The form the application writes, for a value that arrived from anywhere. */
    public static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return CREATED;
        }
        return status.trim().toLowerCase(Locale.ROOT);
    }
}
