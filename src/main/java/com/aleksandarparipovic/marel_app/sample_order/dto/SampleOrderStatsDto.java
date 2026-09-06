package com.aleksandarparipovic.marel_app.sample_order.dto;

/**
 * The counts shown above the sample-order list — computed over every
 * non-archived sample order, not the page the reader has filtered to, so the
 * figures answer "what across all sample orders needs attention now".
 *
 * @param total   every non-archived sample order, open or closed
 * @param open    orders still {@code created} — "otvoreni"
 * @param closed  orders already {@code closed}
 * @param late    open orders whose rok is in the past
 * @param dueSoon open orders due within three days, today included
 */
public record SampleOrderStatsDto(
        long total,
        long open,
        long closed,
        long late,
        long dueSoon
) {
}
