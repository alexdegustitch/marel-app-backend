package com.aleksandarparipovic.marel_app.user;

/**
 * Authoritative account workflow state, persisted as {@code users.account_status}.
 *
 * <p>These states are mutually exclusive by construction — there is deliberately
 * no {@code isApproved}/{@code isDeclined} boolean pair that could disagree.
 * {@code users.is_active} still exists for existing queries, DTOs and the
 * frontend, but it is DERIVED from this value by the database trigger
 * {@code trg_00_users_account_status_sync} and can never contradict it.
 *
 * <p>An ACTIVE account is a user who is <em>allowed</em> to use the application.
 * It says nothing about whether they are currently online — presence is derived
 * from {@code user_sessions}.
 */
public enum UserAccountStatus {

    /** Registered, waiting for an administrator. No access to business endpoints. */
    PENDING_APPROVAL,

    /** Approved and usable. The only status that permits authentication. */
    ACTIVE,

    /** An administrator refused the registration. Terminal unless re-registered. */
    DECLINED,

    /** Was active, then administratively disabled. Reversible. */
    SUSPENDED,

    /** Retired account, kept for historical and audit references. */
    ARCHIVED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
