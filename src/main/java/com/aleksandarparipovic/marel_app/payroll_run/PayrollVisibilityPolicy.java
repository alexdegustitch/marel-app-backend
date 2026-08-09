package com.aleksandarparipovic.marel_app.payroll_run;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Who may see what a payroll is worth, and who may see that it is locked.
 *
 * <p>Two separate facts, both withheld from everyone but payroll itself:
 *
 * <ul>
 *   <li>THE AMOUNTS. A supervisor runs the shop floor and needs to know whether
 *       a month is still being prepared or is done; what somebody is paid is not
 *       part of that job. The figures are therefore removed from the RESPONSE,
 *       not hidden by the screen — a column the browser never receives cannot be
 *       read out of the network tab.</li>
 *   <li>THE LOCK. Locking is payroll's internal step, and for everyone else a
 *       locked month and an approved one mean the same thing: finished, nothing
 *       further expected of them. Reporting {@code LOCKED} would expose an
 *       administrative action they cannot act on, so it is reported as
 *       {@code APPROVED} — the state they already know as "ready".</li>
 * </ul>
 *
 * <p>The check is on the ROLE the user actually authenticated with. Role names
 * live in the database (admin, supervisor, commercial, developer) and reach
 * Spring as {@code ROLE_<name>}, so both spellings are matched here and the
 * comparison is case-insensitive.
 */
@Component
public class PayrollVisibilityPolicy {

    /** The roles payroll belongs to. Everyone else sees status only. */
    private static final Set<String> PRIVILEGED = Set.of("admin", "developer");

    /** Reported instead of LOCKED to anyone who may not know about the lock. */
    static final String LOCKED = "LOCKED";
    static final String APPROVED = "APPROVED";

    /** Whether the current user may see payroll amounts and the locked state. */
    public boolean canSeeAmounts() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(PayrollVisibilityPolicy::isPrivileged);
    }

    private static boolean isPrivileged(String authority) {
        if (authority == null) return false;
        String role = authority.toLowerCase();
        if (role.startsWith("role_")) {
            role = role.substring("role_".length());
        }
        return PRIVILEGED.contains(role);
    }

    /**
     * The status to report. Identity for privileged users; LOCKED collapses into
     * APPROVED for everyone else, so "ready" is all they ever see.
     */
    public String visibleStatus(String status) {
        if (canSeeAmounts() || status == null) {
            return status;
        }
        return LOCKED.equalsIgnoreCase(status) ? APPROVED : status;
    }
}
