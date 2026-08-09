package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_field_access.PayrollFieldAccessRepository;
import com.aleksandarparipovic.marel_app.payroll_field_access.PayrollFieldAccessService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which payroll lines a role may see, and who decides.
 *
 * <p>The table starts empty and that is the whole safety property: a missing
 * row means hidden, so introducing the configuration changes nobody's screen
 * until an administrator grants something, and a new adjustment category is
 * invisible outside payroll the day it is added rather than appearing by
 * accident.
 */
@Transactional
class PayrollFieldAccessIT extends AbstractIntegrationTest {

    @Autowired private PayrollFieldAccessService service;
    @Autowired private PayrollFieldAccessRepository repository;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(String roleName) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p",
                        List.of(new SimpleGrantedAuthority("ROLE_" + roleName))));
    }

    @Test
    @DisplayName("with nothing configured, a supervisor may see no line")
    void defaultsToHidden() {
        signedInAs("supervisor");

        assertThat(service.accessTo("MEAL_ALLOWANCE").canView()).isFalse();
        assertThat(service.accessTo(PayrollFieldAccessService.FIELD_NET_PAYABLE).canView()).isFalse();
    }

    @Test
    @DisplayName("payroll bypasses the table entirely")
    void payrollSeesEverything() {
        signedInAs("admin");

        // Not "has rows granting everything" — no rows at all, and still yes.
        assertThat(repository.findForRole("admin")).isEmpty();
        assertThat(service.accessTo("MEAL_ALLOWANCE").canView()).isTrue();
        assertThat(service.accessTo("ANYTHING_ADDED_TOMORROW").canEdit()).isTrue();
    }

    @Test
    @DisplayName("a granted line becomes visible to that role and to nobody else")
    void grantIsPerRole() {
        signedInAs("admin");
        service.set("MEAL_ALLOWANCE", "supervisor", true, false);

        signedInAs("supervisor");
        assertThat(service.accessTo("MEAL_ALLOWANCE").canView()).isTrue();
        assertThat(service.accessTo("MEAL_ALLOWANCE").canEdit()).isFalse();

        signedInAs("commercial");
        assertThat(service.accessTo("MEAL_ALLOWANCE").canView()).isFalse();
    }

    @Test
    @DisplayName("editing without seeing is not a state that can be stored")
    void editRequiresView() {
        signedInAs("admin");

        var saved = service.set("MEAL_ALLOWANCE", "supervisor", false, true);

        // Coerced rather than refused with a constraint violation the caller
        // cannot read; the database refuses it too, as the second line of defence.
        assertThat(saved.isCanEdit()).isFalse();
    }

    @Test
    @DisplayName("payroll's own roles cannot be given a row")
    void payrollRolesAreNotConfigurable() {
        signedInAs("admin");

        // An administrator must not be able to switch payroll's own access off.
        assertThatThrownBy(() -> service.set("MEAL_ALLOWANCE", "admin", false, false))
                .isInstanceOf(Exception.class);
    }
}
