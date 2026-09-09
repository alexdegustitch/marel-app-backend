package com.aleksandarparipovic.marel_app.search.dto;

/** Minimal customer row for the global search. */
public interface CustomerSearchRow {
    Long getId();
    String getName();
    String getCode();
    String getTaxId();
}
