package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/** How many active lines of one order have no agreed scope. */
public interface UnscopedLineCountRow {
    Long getOrderId();
    Long getLineCount();
}
