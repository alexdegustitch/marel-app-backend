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

    /**
     * See the administrator's control board.
     *
     * <p>Its own capability rather than a check for "is an admin": the board
     * gathers payroll readiness, registrations and orders on one screen, so who
     * may see it is a decision that belongs beside the others in
     * {@link RolePermissions}.
     */
    DASHBOARD_ADMIN_VIEW,

    /**
     * See the supervisor's control board.
     *
     * <p>Separate from {@link #DASHBOARD_ADMIN_VIEW} because the two boards are two
     * different screens answering two different people's questions, not one screen
     * with rows hidden from somebody. Granting one must never imply the other.
     */
    DASHBOARD_SUPERVISOR_VIEW,

    /**
     * Recompute the daily analytics snapshot on demand.
     *
     * <p>Maintenance rather than a feature: it writes the whole snapshot, and exists
     * so a changed threshold can be tried without waiting for the next morning.
     */
    DASHBOARD_INSIGHTS_RECOMPUTE,

    /** Approve or decline a pending self-registration. */
    USER_REGISTRATION_APPROVE,

    /** See every user's registration request, not just one's own. */
    USER_REGISTRATION_READ_ALL,

    /**
     * Say which worker a sign-in account belongs to, or cut that link.
     *
     * <p>Deliberately its OWN capability rather than part of editing a user.
     * Editing a user sets roles and passwords; this sets one field. Supervisors
     * are meant to be able to do this — they are the ones who know who on the
     * floor is who — and must not thereby be able to hand themselves the admin
     * role or reset somebody's password, which is exactly what widening
     * {@code PATCH /api/users/{id}} to them would have allowed.
     *
     * <p>The consequence of getting it wrong is one person reading another
     * person's payslip, so the two endpoints it guards do nothing else.
     */
    USER_EMPLOYEE_LINK,

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
    PAYROLL_LOCK,

    /**
     * Decide which payroll lines each role may see and change.
     *
     * <p>Payroll's own, and nobody else's: this is the control over who sees
     * salaries, so granting it is granting the ability to grant.
     */
    PAYROLL_ACCESS_CONFIGURE,

    /**
     * Withdraw a whole shift, or delete one that never held anything.
     *
     * <p>Its own permission because it is not a correction: taking a shift back
     * removes a day of work from what somebody is paid, which is a heavier
     * decision than fixing the hours on it. Admin and developer hold every
     * permission; nobody else is given this one.
     */
    WORK_SHIFT_ARCHIVE
}
