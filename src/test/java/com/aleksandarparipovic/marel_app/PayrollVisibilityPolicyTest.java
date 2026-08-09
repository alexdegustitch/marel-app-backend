package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_run.PayrollVisibilityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may see what a payroll is worth, and who may see that it is locked.
 *
 * <p>Both are withheld from everyone but payroll. The amounts are removed from
 * the response rather than hidden by the screen, and the lock is reported as
 * APPROVED — the state the rest of the company already knows as "ready".
 */
class PayrollVisibilityPolicyTest {

    private final PayrollVisibilityPolicy policy = new PayrollVisibilityPolicy();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(String roleName) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "u", "p", List.of(new SimpleGrantedAuthority("ROLE_" + roleName))));
    }

    @Test
    @DisplayName("payroll roles see the amounts and the lock")
    void payrollRolesSeeEverything() {
        for (String role : List.of("admin", "developer", "ADMIN")) {
            signedInAs(role);
            assertThat(policy.canSeeAmounts()).as(role).isTrue();
            assertThat(policy.visibleStatus("LOCKED")).as(role).isEqualTo("LOCKED");
        }
    }

    @Test
    @DisplayName("everyone else sees neither")
    void otherRolesSeeNeither() {
        for (String role : List.of("supervisor", "commercial")) {
            signedInAs(role);
            assertThat(policy.canSeeAmounts()).as(role).isFalse();
            // A locked month reads as approved: finished, nothing expected of them.
            assertThat(policy.visibleStatus("LOCKED")).as(role).isEqualTo("APPROVED");
            // Everything else passes through — a draft is still a draft.
            assertThat(policy.visibleStatus("DRAFT")).as(role).isEqualTo("DRAFT");
            assertThat(policy.visibleStatus("APPROVED")).as(role).isEqualTo("APPROVED");
        }
    }

    @Test
    @DisplayName("with nobody signed in the answer is no")
    void failsClosed() {
        SecurityContextHolder.clearContext();
        assertThat(policy.canSeeAmounts()).isFalse();
        assertThat(policy.visibleStatus("LOCKED")).isEqualTo("APPROVED");
    }

    /*
     * The two questions need OPPOSITE defaults, and conflating them broke the
     * system's own work: recalculation, payroll initialisation and every
     * integration test call the service with no SecurityContext, so a write
     * guard built on canSeeAmounts() refused them as "a user without the right".
     */
    @Test
    @DisplayName("the system is not a user without rights — write guards let it through")
    void systemCallsAreNotRestrictedUsers() {
        SecurityContextHolder.clearContext();

        assertThat(policy.hasAuthenticatedUser()).isFalse();
        assertThat(policy.isRestrictedUser()).isFalse();
        // Masking still hides, which is the harmless default.
        assertThat(policy.canSeeAmounts()).isFalse();
    }

    @Test
    @DisplayName("a signed-in supervisor IS a restricted user")
    void supervisorIsRestricted() {
        signedInAs("supervisor");
        assertThat(policy.isRestrictedUser()).isTrue();
    }

    @Test
    @DisplayName("payroll is never a restricted user")
    void payrollIsNotRestricted() {
        signedInAs("admin");
        assertThat(policy.isRestrictedUser()).isFalse();
    }

    @Test
    @DisplayName("an anonymous token is not a signed-in user")
    void anonymousIsNotAUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.AnonymousAuthenticationToken(
                        "key", "anonymous",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(policy.hasAuthenticatedUser()).isFalse();
        assertThat(policy.isRestrictedUser()).isFalse();
    }
}
