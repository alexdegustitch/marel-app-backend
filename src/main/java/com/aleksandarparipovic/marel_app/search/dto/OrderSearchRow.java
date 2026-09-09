package com.aleksandarparipovic.marel_app.search.dto;

/** Minimal production-order row for the global search. */
public interface OrderSearchRow {
    Long getId();
    String getName();
    String getCode();
    String getCustomerName();
}
