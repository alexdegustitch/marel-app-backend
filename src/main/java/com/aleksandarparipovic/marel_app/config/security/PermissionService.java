package com.aleksandarparipovic.marel_app.config.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Answers "may the current caller do X?" for {@code @PreAuthorize}.
 *
 * <p>Registered as the bean name {@code perm} so expressions read:
 * <pre>{@code @PreAuthorize("@perm.has('USER_REGISTRATION_APPROVE')")}</pre>
 *
 * <p>{@code @EnableMethodSecurity} is already switched on in {@code SecurityConfig},
 * so no configuration change is needed to use this.
 */
@Component("perm")
public class PermissionService {

    private static final String ROLE_PREFIX = "ROLE_";

    /** Used by {@code @PreAuthorize}; the name is a permission constant. */
    public boolean has(String permissionName) {
        AppPermission permission;
        try {
            permission = AppPermission.valueOf(permissionName);
        } catch (IllegalArgumentException ex) {
            // An unknown permission name is a programming error in an annotation.
            // Fail closed rather than silently granting access.
            return false;
        }
        return hasPermission(permission);
    }

    public boolean hasPermission(AppPermission permission) {
        return currentRoleNames().stream()
                .anyMatch(role -> RolePermissions.roleHas(role, permission));
    }

    /**
     * Role names that grant the permission — the input to "who should be notified
     * about this event", so that recipients are resolved from capability rather
     * than from a hard-coded role name at the call site.
     */
    public List<String> roleNamesWith(AppPermission permission) {
        return java.util.Arrays.stream(RoleNames.ALL)
                .filter(role -> RolePermissions.roleHas(role, permission))
                .toList();
    }

    private Set<String> currentRoleNames() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(ROLE_PREFIX))
                .map(a -> a.substring(ROLE_PREFIX.length()).toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * The role names that exist in the {@code roles} table. Kept here rather than
     * read from the database because permission mapping is static configuration,
     * and resolving notification recipients must not depend on a database read
     * that could silently return nothing.
     */
    static final class RoleNames {
        static final String[] ALL = {"admin", "supervisor", "commercial", "developer"};

        private RoleNames() {
        }
    }
}
