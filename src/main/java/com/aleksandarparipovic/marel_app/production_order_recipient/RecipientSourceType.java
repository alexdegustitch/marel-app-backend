package com.aleksandarparipovic.marel_app.production_order_recipient;

/** Where a production-order recipient came from. */
public enum RecipientSourceType {

    /** Copied from a selected mailing list at attach time. */
    MAILING_LIST,

    /** Typed in by a user for this order only. */
    MANUAL,

    /** Added by backend logic; has no human author. */
    SYSTEM
}
