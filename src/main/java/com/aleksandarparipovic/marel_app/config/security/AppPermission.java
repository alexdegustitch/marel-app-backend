package com.aleksandarparipovic.marel_app.config.security;

/**
 * Named capabilities used by {@code @PreAuthorize} instead of hard-coded role names.
 *
 * <p>This project has no permission table: authorization is role-based
 * ({@code CustomUserDetails} grants a single {@code ROLE_<roleName>} authority and
 * {@code SecurityConfig} checks {@code hasRole}). Introducing a permission table
 * would mean replacing the existing authorization model, which is out of scope.
 *
 * <p>This enum is therefore a naming layer over the existing roles, not a second
 * authorization model. Endpoints express <em>what</em> a caller must be allowed to
 * do; {@link RolePermissions} is the single place that says which roles may do it.
 * When a real permission model is eventually introduced, only
 * {@link RolePermissions} has to change.
 */
public enum AppPermission {

    /** Approve or decline a pending self-registration. */
    USER_REGISTRATION_APPROVE,

    /** See every user's registration request, not just one's own. */
    USER_REGISTRATION_READ_ALL,

    /** Claim, complete or decline a manufacturing-time request. */
    MANUFACTURING_TIME_REQUEST_PROCESS,

    /** See every manufacturing-time request, not just one's own. */
    MANUFACTURING_TIME_REQUEST_READ_ALL,

    /** Read and edit mailing lists with GLOBAL visibility. */
    MAILING_LIST_GLOBAL_MANAGE,

    /** Attach mailing lists to, and manage recipients of, a production order. */
    PRODUCTION_ORDER_RECIPIENT_MANAGE,

    /** Revoke another user's session. */
    USER_SESSION_REVOKE,

    /** Read or modify another user's preferences. */
    USER_PREFERENCES_ADMIN,

    /**
     * Recalculate every payroll item in one sweep.
     *
     * <p>Maintenance, not a feature: it exists so a model change can be rolled
     * across data that is otherwise only recalculated when somebody opens it. It
     * writes to every unlocked item, so it belongs to whoever owns the payroll —
     * RolePermissions grants it to admin and developer and to nobody else.
     */
    PAYROLL_MAINTENANCE_RECALCULATE,

    /**
     * Hand a payroll month over as finished, or send it back for correction.
     *
     * <p>The shop floor's own step: the supervisor is the one who knows whether
     * the month's work is complete, so they hold this and payroll does too.
     */
    PAYROLL_HANDOVER,

    /**
     * Freeze a handed-over month, or reopen it.
     *
     * <p>Payroll's step, and only theirs — locking is what turns a calculated
     * month into a record of what was paid.
     */
    PAYROLL_LOCK
}
