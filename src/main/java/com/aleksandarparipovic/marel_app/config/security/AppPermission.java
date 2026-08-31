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
     * Open the work records — the cards, the months behind them and the shifts,
     * hours and work logs they are built from.
     *
     * <p>The screens this covers are where a worker's day is written down and
     * corrected, so it is held by the people who own that record: payroll and
     * the shop floor. It is deliberately ONE capability rather than a read and a
     * write half — everybody who may open a card may also correct it, and a
     * read-only card would be a screen nobody in this company needs.
     */
    WORK_RECORD_VIEW,

    /**
     * Open the payroll screens — the runs, their items and the adjustments on
     * them.
     *
     * <p>NOT the same thing as seeing the AMOUNTS. Which lines of a payroll each
     * role may read is decided per field by {@code payroll_field_access} and by
     * {@code PayrollVisibilityPolicy}, and this permission changes none of that.
     * It says only who may open the screens at all; what they find there is
     * still filtered underneath.
     *
     * <p>Kept separate from {@link #PAYROLL_LOCK} and {@link #PAYROLL_HANDOVER}
     * for that reason: opening the month, handing it over and freezing it are
     * three different decisions belonging to three different people.
     */
    PAYROLL_VIEW,

    /**
     * Record or change a product's manufacturing time.
     *
     * <p>Writing only. Reading one back is deliberately left open, because the
     * person who ASKED for a manufacturing time downloads its report from the
     * requests screen — the answer belongs to whoever raised the question, and
     * restricting the read would have taken their own answer away from them.
     */
    MANUFACTURING_TIME_MANAGE,

    /**
     * Open the analytics screens.
     *
     * <p>Wider than the records they are computed from: the production
     * coordinator plans against efficiency figures without having any business
     * in an individual worker's card. That is exactly why this is its own
     * capability and not a consequence of {@link #WORK_RECORD_VIEW}.
     */
    ANALYTICS_VIEW,

    /**
     * See the workers — the list, one worker's page, and their work calendar.
     *
     * <p>Distinct from the user directory, which everybody signed in may read. A
     * user is somebody who signs in; an employee is somebody the factory pays,
     * and their page carries employment periods, categories and compensation.
     */
    EMPLOYEE_VIEW,

    /** See the customers the factory makes things for. */
    CUSTOMER_VIEW,

    /**
     * See production orders and everything hanging off them.
     *
     * <p>Split from {@link #PRODUCTION_ORDER_MANAGE} because the supervisor is
     * meant to READ every order — they have to know what the floor is making —
     * without being able to raise or alter one. Reading and writing an order are
     * two different jobs held by two different people, so they are two
     * permissions.
     */
    PRODUCTION_ORDER_VIEW,

    /** Raise, alter or advance a production order. */
    PRODUCTION_ORDER_MANAGE,

    /**
     * Raise a manufacturing-time request.
     *
     * <p>Held by everybody EXCEPT the supervisor, who decides requests: whoever
     * decides them does not raise them, which is the rule the requests screen
     * has always followed. Administrators hold it because they are also the ones
     * who have to be able to try the workflow end to end.
     */
    MANUFACTURING_TIME_REQUEST_CREATE,

    /** Read and change the monthly bonus rules. */
    BONUS_RULE_MANAGE,

    /** Read and change the application-wide parameters. */
    APP_SETTING_MANAGE,

    /**
     * Enter or change a period in the work calendar.
     *
     * <p>Reading the calendar is open to everybody signed in — a person has to
     * be able to see which days the factory works. Only this half is restricted.
     */
    WORK_CALENDAR_MANAGE,

    /**
     * See the commercial control board.
     *
     * <p>Its own screen for its own question, on the same reasoning as the two
     * boards above: granting one board must never imply another.
     */
    DASHBOARD_COMMERCIAL_VIEW,

    /** See the production coordinator's control board. */
    DASHBOARD_PRODUCTION_COORDINATOR_VIEW,

    /**
     * Create a product, or change one.
     *
     * <p>Reading the catalogue is open to everybody signed in — most of the
     * company has to be able to look a product up. Writing to it is the shop
     * floor's and the administration's, because a product is what the norms,
     * the operations and the manufacturing times all hang off.
     */
    PRODUCT_MANAGE,

    /**
     * Create or change an operation, including its norms and their versions.
     *
     * <p>Separate from {@link #PRODUCT_MANAGE} rather than one "catalogue"
     * capability: granting the right to add a product must not silently grant
     * the right to change the norm a person is paid against.
     */
    OPERATION_MANAGE,

    /**
     * See who gets told about a production order.
     *
     * <p>Split out of {@link #PRODUCTION_ORDER_RECIPIENT_MANAGE}, which used to
     * guard reading the recipient list as well as changing it. The supervisor
     * has to be able to SEE who was informed about an order they are running,
     * and must not be able to add somebody to that list or take somebody off it
     * — the same read/write split the order itself has.
     */
    PRODUCTION_ORDER_RECIPIENT_VIEW,

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

    /**
     * Ask the floor which operations a production order actually needs.
     *
     * <p>Held by the same people who raise manufacturing-time requests, and
     * withheld from the supervisor for the same reason: whoever decides a
     * request does not raise it.
     *
     * <p>Deliberately its OWN capability rather than a reuse of
     * {@link #MANUFACTURING_TIME_REQUEST_CREATE}. The two workflows answer
     * different questions — how long one piece takes, and what this order is
     * made of — and one day one of them may be opened to somebody the other is
     * not. Sharing a capability today would make that a change to who holds
     * which role.
     */
    ORDER_SCOPE_REQUEST_CREATE,

    /** Claim, answer or decline an order-scope request. */
    ORDER_SCOPE_REQUEST_PROCESS,

    /** See every order-scope request, not just one's own. */
    ORDER_SCOPE_REQUEST_READ_ALL,

    /** Read and edit mailing lists with GLOBAL visibility. */
    MAILING_LIST_GLOBAL_MANAGE,

    /**
     * Attach mailing lists to, and change the recipients of, a production order.
     *
     * <p>The WRITE half. Seeing the list is
     * {@link #PRODUCTION_ORDER_RECIPIENT_VIEW}, which everybody holding this
     * also holds.
     */
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
     * Ask payroll to reopen a month that has already been handed over.
     *
     * <p>The supervisor's replacement for pulling it back themselves. Once
     * payroll has begun working on a submitted month, taking it away underneath
     * them invalidates whatever they have done since — so the direction reverses
     * and the supervisor asks, with a reason.
     */
    PAYROLL_CHANGE_REQUEST_CREATE,

    /**
     * Answer such a request — grant the month back, or refuse.
     *
     * <p>Payroll's, on the same reasoning as {@link #PAYROLL_LOCK}: whoever owns
     * the month decides when it opens. Accepting takes the payroll to DRAFT, so
     * this is a status change wearing a different name and belongs to the same
     * people.
     */
    PAYROLL_CHANGE_REQUEST_PROCESS,

    /**
     * Read and write the director's note that goes on the payslip.
     *
     * <p>Administrators alone. Narrower than the payroll screens themselves,
     * which the supervisor and payroll both open: this is a sentence signed by
     * the director and printed over their name, and a note somebody else can
     * edit is not that.
     *
     * <p>Narrower on SCREEN than on paper, deliberately. Withholding it here
     * keeps it out of the application for everybody but the author; the payslip
     * that carries it to the employee is the one the administration renders and
     * hands over.
     */
    PAYROLL_DIRECTOR_NOTE,

    /**
     * Give or change the performance mark (ocena) on a payroll item.
     *
     * <p>Held by the supervisor as well as payroll, and deliberately: the mark
     * is an opinion about the month's work, and the person who knows the work is
     * the one on the floor. Giving it moves no money — {@link #PAYROLL_MARK_APPLY}
     * is what does — which is exactly what makes it safe to grant this widely.
     */
    PAYROLL_MARK_EDIT,

    /**
     * Put a performance mark in force, or take it out of force.
     *
     * <p>THIS is the one that changes what somebody is paid: the hourly rate
     * becomes the base multiplied by the mark. Payroll's alone, on the same
     * reasoning as {@link #PAYROLL_LOCK} — the supervisor says what the month
     * was worth, and payroll decides whether that becomes money.
     */
    PAYROLL_MARK_APPLY,

    /**
     * Decide which payroll lines each role may see and change.
     *
     * <p>Payroll's own, and nobody else's: this is the control over who sees
     * salaries, so granting it is granting the ability to grant.
     */
    PAYROLL_ACCESS_CONFIGURE,

    /**
     * See sample orders — nalozi za izradu uzoraka — and everything hanging off
     * them.
     *
     * <p>Its OWN capability rather than a reuse of {@link #PRODUCTION_ORDER_VIEW},
     * even though the same people hold both today. The two are different
     * documents for different work: samples are agreed with a customer before
     * anything is ordered, and the day somebody may see one and not the other,
     * that has to be a change to this file rather than a change to who holds
     * which role.
     */
    SAMPLE_ORDER_VIEW,

    /**
     * Raise, alter or close a sample order.
     *
     * <p>Split from {@link #SAMPLE_ORDER_VIEW} for the same reason production
     * orders are split: the supervisor reads every sample order — they have to
     * know what the floor is making — and writes none.
     */
    SAMPLE_ORDER_MANAGE,

    /** See who gets told about a sample order. The read half. */
    SAMPLE_ORDER_RECIPIENT_VIEW,

    /**
     * Attach mailing lists to, and change the recipients of, a sample order.
     *
     * <p>The WRITE half. Seeing the list is {@link #SAMPLE_ORDER_RECIPIENT_VIEW},
     * which everybody holding this also holds.
     */
    SAMPLE_ORDER_RECIPIENT_MANAGE,

    /**
     * Withdraw a whole shift, or delete one that never held anything.
     *
     * <p>Its own permission because it is not a correction: taking a shift back
     * removes a day of work from what somebody is paid, which is a heavier
     * decision than fixing the hours on it.
     *
     * <p>Held by the supervisor as well as admin and developer: they enter the
     * shifts and they are the ones who spot a wrong one. What keeps that safe is
     * not the permission but the MONTH — a payroll already handed over or locked
     * refuses the removal, so this only ever reaches a month still being worked
     * on.
     */
    WORK_SHIFT_ARCHIVE
}
