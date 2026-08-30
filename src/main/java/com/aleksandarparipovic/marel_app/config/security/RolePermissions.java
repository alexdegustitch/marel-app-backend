package com.aleksandarparipovic.marel_app.config.security;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single mapping from an existing role name to the capabilities it grants.
 *
 * <p>Role names are the ones that actually exist in the {@code roles} table:
 * {@code admin}, {@code supervisor}, {@code commercial}, {@code developer},
 * {@code production_coordinator} and {@code accountant}. {@code V15} inserts all
 * six, so a database built from the migrations matches the live one. Comparison
 * is case-insensitive, matching {@code RoleService}, which already compares role
 * names with {@code equalsIgnoreCase}.
 *
 * <p>A role that is absent from this map holds NOTHING. That is the correct
 * default and not an oversight: a role nobody has decided about must not
 * accidentally inherit somebody else's screens.
 */
public final class RolePermissions {

    private static final Set<AppPermission> ALL = EnumSet.allOf(AppPermission.class);

    private static final Map<String, Set<AppPermission>> BY_ROLE = Map.of(
            // Administrators run user approval and own every cross-cutting setting.
            "admin", ALL,

            // "developer" is an internal engineering account (RoleService excludes it
            // from public registration). It mirrors admin so support work is possible.
            "developer", ALL,

            /*
             * Supervisors run the shop floor. They own the work records and the
             * payroll screens the floor's work feeds, the manufacturing-time
             * workflow, the analytics, the workers, and the settings behind all of
             * it — but never user approval, never session revocation, and never
             * the configuration of who may see which payroll line.
             *
             * On ORDERS they read and do not write. That is the split
             * PRODUCTION_ORDER_VIEW exists for: they have to know what the floor is
             * making, and raising or altering an order is commercial work. The same
             * split now runs through the RECIPIENTS of an order: they see who was
             * told about it and change nobody.
             *
             * They also do NOT hold MANUFACTURING_TIME_REQUEST_CREATE. Whoever
             * decides requests does not raise them.
             *
             * They DO write to the catalogue — products, operations and the norms
             * on them. Reading it is open to the whole company; changing what a
             * person is paid against is not.
             */
            "supervisor", EnumSet.of(
                    AppPermission.DASHBOARD_SUPERVISOR_VIEW,
                    AppPermission.WORK_RECORD_VIEW,
                    AppPermission.PAYROLL_VIEW,
                    AppPermission.MANUFACTURING_TIME_MANAGE,
                    AppPermission.ANALYTICS_VIEW,
                    AppPermission.EMPLOYEE_VIEW,
                    AppPermission.PRODUCT_MANAGE,
                    AppPermission.OPERATION_MANAGE,
                    AppPermission.PRODUCTION_ORDER_VIEW,
                    AppPermission.PRODUCTION_ORDER_RECIPIENT_VIEW,
                    /*
                     * Sample orders follow the production order exactly: read
                     * every one, alter none, and see who was told without being
                     * able to change it. Granted separately rather than implied,
                     * so that opening one kind of order to somebody never
                     * silently opens the other.
                     */
                    AppPermission.SAMPLE_ORDER_VIEW,
                    AppPermission.SAMPLE_ORDER_RECIPIENT_VIEW,
                    AppPermission.BONUS_RULE_MANAGE,
                    AppPermission.APP_SETTING_MANAGE,
                    AppPermission.WORK_CALENDAR_MANAGE,
                    AppPermission.MANUFACTURING_TIME_REQUEST_PROCESS,
                    AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL,
                    /*
                     * The same two halves for the order-scope workflow: the
                     * supervisor is the one who knows which operations a variant
                     * really needs, so they ANSWER these requests and, by the same
                     * rule as above, never raise one.
                     */
                    AppPermission.ORDER_SCOPE_REQUEST_PROCESS,
                    AppPermission.ORDER_SCOPE_REQUEST_READ_ALL,
                    /*
                     * NOT MAILING_LIST_GLOBAL_MANAGE, and no longer
                     * PRODUCTION_ORDER_RECIPIENT_MANAGE.
                     *
                     * Both were granted while "supervisor reads orders and writes
                     * none" did not yet exist as a rule. That one permission gated
                     * USING a global mailing list and MANAGING one — editing and
                     * archiving somebody else's, promoting one's own — which is
                     * writing, on an order, by a role that no longer writes on
                     * orders. The read half they actually need is
                     * PRODUCTION_ORDER_RECIPIENT_VIEW above.
                     */
                    // Hands the month over and takes it back; never locks it.
                    AppPermission.PAYROLL_HANDOVER,
                    /*
                     * Gives the performance mark, and does NOT apply it. They
                     * know what the month's work was worth; turning that opinion
                     * into money is payroll's step, exactly as locking is.
                     */
                    AppPermission.PAYROLL_MARK_EDIT,
                    /*
                     * Asks for a handed-over month back, and no longer takes it.
                     * PAYROLL_HANDOVER still lets them SUBMIT; returning a
                     * submitted month is now payroll's answer to this request,
                     * because payroll may already have worked on it.
                     */
                    AppPermission.PAYROLL_CHANGE_REQUEST_CREATE,
                    // Says which worker an account belongs to. They know the floor,
                    // so they are the ones who can answer it — and this grants that
                    // one field, not the editing of accounts.
                    AppPermission.USER_EMPLOYEE_LINK),

            // Commercial staff drive production orders, the customers behind them
            // and who gets told about them. Nothing about the shop floor, beyond
            // reading the manufacturing-time requests they share a queue with.
            "commercial", EnumSet.of(
                    AppPermission.DASHBOARD_COMMERCIAL_VIEW,
                    AppPermission.CUSTOMER_VIEW,
                    AppPermission.PRODUCTION_ORDER_VIEW,
                    AppPermission.PRODUCTION_ORDER_MANAGE,
                    AppPermission.PRODUCTION_ORDER_RECIPIENT_VIEW,
                    AppPermission.PRODUCTION_ORDER_RECIPIENT_MANAGE,
                    // Commercial staff write the sample orders too — samples are
                    // agreed with a customer, which is their work end to end.
                    AppPermission.SAMPLE_ORDER_VIEW,
                    AppPermission.SAMPLE_ORDER_MANAGE,
                    AppPermission.SAMPLE_ORDER_RECIPIENT_VIEW,
                    AppPermission.SAMPLE_ORDER_RECIPIENT_MANAGE,
                    AppPermission.MANUFACTURING_TIME_REQUEST_CREATE,
                    /*
                     * They READ every manufacturing-time request, not only the ones
                     * they raised. The requests screen is a shared queue: commercial
                     * raises the work, the supervisor decides it, and both have to
                     * be looking at the same list to know what is still waiting.
                     * Deciding is still MANUFACTURING_TIME_REQUEST_PROCESS, which
                     * they do NOT hold — this widens what they see, not what they do.
                     */
                    AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL,
                    /*
                     * They raise the order-scope requests and read the whole
                     * queue, exactly as with manufacturing times: commercial asks
                     * what the order is made of, the floor answers, and both watch
                     * the same list. Deciding is ORDER_SCOPE_REQUEST_PROCESS,
                     * which they do NOT hold.
                     */
                    AppPermission.ORDER_SCOPE_REQUEST_CREATE,
                    AppPermission.ORDER_SCOPE_REQUEST_READ_ALL,
                    // Same grant, same caveat as above: using and managing a global
                    // list are one permission here.
                    AppPermission.MAILING_LIST_GLOBAL_MANAGE),

            /*
             * The production coordinator plans against the numbers. Analytics and
             * their own board — deliberately NOT the work records those numbers are
             * computed from, which carry one named worker's day.
             */
            "production_coordinator", EnumSet.of(
                    AppPermission.DASHBOARD_PRODUCTION_COORDINATOR_VIEW,
                    AppPermission.ANALYTICS_VIEW,
                    AppPermission.MANUFACTURING_TIME_REQUEST_CREATE,
                    /*
                     * Granted alongside the manufacturing-time one so the two
                     * request workflows are open to the same people. Reaching an
                     * order to raise it from is still PRODUCTION_ORDER_VIEW, which
                     * this role does not hold — this widens nothing on its own.
                     */
                    AppPermission.ORDER_SCOPE_REQUEST_CREATE),

            /*
             * accountant holds nothing yet.
             *
             * The role exists in the database and no rule has been decided for it,
             * so it gets the screens EVERY signed-in account gets — the directory,
             * products, operations, the work calendar, the requests screen — and
             * nothing more. Listed explicitly rather than left out, so that reading
             * this file answers "what about accountant?" instead of leaving it to be
             * discovered as a missing key.
             */
            "accountant", EnumSet.of(
                    AppPermission.MANUFACTURING_TIME_REQUEST_CREATE,
                    AppPermission.ORDER_SCOPE_REQUEST_CREATE)
    );

    private RolePermissions() {
    }

    public static Set<AppPermission> forRole(String roleName) {
        if (roleName == null) {
            return Set.of();
        }
        return BY_ROLE.getOrDefault(roleName.toLowerCase(Locale.ROOT), Set.of());
    }

    public static boolean roleHas(String roleName, AppPermission permission) {
        return forRole(roleName).contains(permission);
    }
}
