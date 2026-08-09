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
}
