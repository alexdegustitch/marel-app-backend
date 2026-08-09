package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_field_access.PayrollFieldAccessRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
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
    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollScenarioFixture fixture;

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

    /*
     * The rule the owner stated: the same formula, fewer terms. If
     * a = X + Y - Z and Y is not theirs to see, they get a = X - Z — a total
     * they can check by adding up what is on the screen.
     */
    @Test
    @DisplayName("a restricted reader gets only their lines, and totals that match them")
    void totalsMatchTheVisibleLines() {
        var scenario = fixture.scenario().build();
        Long monthlyReportId = scenario.monthlyReport().getId();

        signedInAs("admin");
        var full = payrollRunItemService.getDetails(monthlyReportId);
        int fullLineCount = full.getAdjustments().stream()
                .mapToInt(section -> section.getAdjustments().size()).sum();
        assertThat(fullLineCount).isGreaterThan(1);

        // One line granted, and only one.
        service.set("MEAL_ALLOWANCE", "supervisor", true, false);

        signedInAs("supervisor");
        var filtered = payrollRunItemService.getDetails(monthlyReportId);

        int visibleLineCount = filtered.getAdjustments().stream()
                .mapToInt(section -> section.getAdjustments().size()).sum();
        assertThat(visibleLineCount).isLessThan(fullLineCount);
        assertThat(filtered.getAdjustments().stream()
                .flatMap(section -> section.getAdjustments().stream())
                .map(a -> a.getCategoryCode()))
                .containsOnly("MEAL_ALLOWANCE");

        // The headline figures are configurable in their own right, and nothing
        // was granted for them.
        assertThat(filtered.getSummary().getNetPayableAmount()).isNull();
        assertThat(filtered.getSummary().getTotalNetEarnings()).isNull();
    }

    @Test
    @DisplayName("the filtered total is the same arithmetic, not a different one")
    void filteredTotalUsesTheSameFormula() {
        var scenario = fixture.scenario().build();
        Long monthlyReportId = scenario.monthlyReport().getId();

        signedInAs("admin");

        // A HIDDEN LINE HAS TO CARRY MONEY, or the assertion proves nothing:
        // with every adjustment at zero the two totals agree no matter what is
        // filtered, and the first version of this test passed for that reason.
        var patch = new com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest();
        var line = new com.aleksandarparipovic.marel_app.payroll_run_item.dto.AdjustmentPatchDto();
        line.setId(scenario.adjustment("OTHER").getId());
        line.setAmount(new java.math.BigDecimal("5000.00"));
        patch.setAdjustments(java.util.List.of(line));
        payrollRunItemService.patch(scenario.item().getId(), patch);

        service.set("MEAL_ALLOWANCE", "supervisor", true, false);
        service.set(PayrollFieldAccessService.FIELD_TOTAL_NET_EARNINGS, "supervisor", true, false);
        var full = payrollRunItemService.getDetails(monthlyReportId);

        signedInAs("supervisor");
        var filtered = payrollRunItemService.getDetails(monthlyReportId);

        // Allowed to see a total now, and it is NOT the full one: the hidden
        // terms are absent from it, which is the whole point.
        assertThat(filtered.getSummary().getTotalNetEarnings()).isNotNull();
        // Exactly the hidden line's 5.000 lighter — the same expression with
        // that term absent, not a different calculation.
        assertThat(full.getSummary().getTotalNetEarnings()
                        .subtract(filtered.getSummary().getTotalNetEarnings()))
                .isEqualByComparingTo(new java.math.BigDecimal("5000.00"));
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
