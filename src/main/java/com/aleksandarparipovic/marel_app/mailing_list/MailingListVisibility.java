package com.aleksandarparipovic.marel_app.mailing_list;

/**
 * Who may see and use a mailing list.
 *
 * <p>These are not permission levels on top of each other: a GLOBAL list is not a
 * "more shared" SHARED list. GLOBAL is gated by an application permission, SHARED
 * by explicit per-user grants.
 */
public enum MailingListVisibility {

    /** Owner only. */
    PRIVATE,

    /** Owner plus users explicitly granted access in mailing_list_access. */
    SHARED,

    /** Any user holding MAILING_LIST_GLOBAL_MANAGE. */
    GLOBAL
}
