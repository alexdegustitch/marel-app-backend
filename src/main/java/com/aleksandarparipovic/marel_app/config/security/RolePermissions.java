package com.aleksandarparipovic.marel_app.config.security;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single mapping from an existing role name to the capabilities it grants.
 *
 * <p>Role names are the ones that actually exist in the {@code roles} table:
 * {@code admin}, {@code supervisor}, {@code commercial}, {@code developer}.
 * Comparison is case-insensitive, matching {@code RoleService}, which already
 * compares role names with {@code equalsIgnoreCase}.
 */
public final class RolePermissions {

    private static final Set<AppPermission> ALL = EnumSet.allOf(AppPermission.class);

    private static final Map<String, Set<AppPermission>> BY_ROLE = Map.of(
            // Administrators run user approval and own every cross-cutting setting.
            "admin", ALL,

            // "developer" is an internal engineering account (RoleService excludes it
            // from public registration). It mirrors admin so support work is possible.
            "developer", ALL,

            // Supervisors own the manufacturing-time workflow and production-order
            // communication, but never user approval or session revocation.
            "supervisor", EnumSet.of(
                    AppPermission.DASHBOARD_SUPERVISOR_VIEW,
                    AppPermission.MANUFACTURING_TIME_REQUEST_PROCESS,
                    AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL,
                    AppPermission.PRODUCTION_ORDER_RECIPIENT_MANAGE,
                    // Hands the month over and takes it back; never locks it.
                    AppPermission.PAYROLL_HANDOVER),

            // Commercial staff drive production orders and who gets told about them.
            "commercial", EnumSet.of(
                    AppPermission.PRODUCTION_ORDER_RECIPIENT_MANAGE)
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
