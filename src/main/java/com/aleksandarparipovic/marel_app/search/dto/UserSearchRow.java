package com.aleksandarparipovic.marel_app.search.dto;

/** Minimal app-user row for the global search. */
public interface UserSearchRow {
    Long getId();
    String getFullName();
    String getDisplayName();
    String getUsername();
    String getRoleName();
}
