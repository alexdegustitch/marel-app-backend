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

    /*
     * The owner's rule, stated twice from two ends: after a handover a
     * supervisor always opens the payroll as they submitted it, and nothing is
     * recalculated for them; back in DRAFT they see the live figures again,
     * filtered to their lines.
     */
    @Test
    @DisplayName("once handed over, a supervisor keeps seeing what they submitted")
    void handedOverIsFrozen() {
        var scenario = fixture.scenario().build();
        Long monthlyReportId = scenario.monthlyReport().getId();
        Long itemId = scenario.item().getId();

        signedInAs("admin");
        service.set("OTHER", "supervisor", true, false);
        service.set(PayrollFieldAccessService.FIELD_TOTAL_NET_EARNINGS, "supervisor", true, false);

        setAmount(itemId, scenario.adjustment("OTHER").getId(), "1000.00");
        payrollRunItemService.submit(itemId, "predato");

        var liveBefore = payrollRunItemService.getDetails(monthlyReportId)
                .getSummary().getTotalNetEarnings();

        signedInAs("supervisor");
        var atHandover = total(payrollRunItemService.frozenDetails(monthlyReportId).orElseThrow());

        // Payroll keeps working after the handover — which is exactly the case
        // the frozen view exists for.
        signedInAs("admin");
        setAmount(itemId, scenario.adjustment("OTHER").getId(), "7777.00");
        // The live payroll really did move, or the assertions below prove nothing.
        assertThat(payrollRunItemService.getDetails(monthlyReportId).getSummary().getTotalNetEarnings())
                .isNotEqualByComparingTo(liveBefore);

        signedInAs("supervisor");
        var frozen = payrollRunItemService.frozenDetails(monthlyReportId);
        assertThat(frozen).isPresent();

        assertThat(lineAmount(frozen.get(), "OTHER"))
                .isEqualByComparingTo("1000.00");
        // Not merely the line: the total they handed over is the one they keep.
        assertThat(total(frozen.get())).isEqualByComparingTo(atHandover);

        // Still only their lines. Freezing is not a way around the filter.
        assertThat(visibleCodes(frozen.get())).containsOnly("OTHER");

        // A lock applied afterwards is not theirs to learn about.
        signedInAs("admin");
        payrollRunItemService.lock(itemId);
        signedInAs("supervisor");
        assertThat(summaryOf(payrollRunItemService.frozenDetails(monthlyReportId).orElseThrow())
                .get("status")).isEqualTo("APPROVED");

        // Back in draft, the frozen view stops applying and they see live
        // figures again — filtered to the same lines.
        signedInAs("admin");
        payrollRunItemService.unlock(itemId);
        payrollRunItemService.returnToDraft(itemId, "vraceno");

        signedInAs("supervisor");
        assertThat(payrollRunItemService.frozenDetails(monthlyReportId)).isEmpty();
        assertThat(payrollRunItemService.getDetails(monthlyReportId).getAdjustments().stream()
                .flatMap(s -> s.getAdjustments().stream())
                .filter(a -> "OTHER".equals(a.getCategoryCode()))
                .findFirst().orElseThrow().getAmount())
                .isEqualByComparingTo("7777.00");
    }

    private void setAmount(Long itemId, Long adjustmentId, String amount) {
        var patch = new com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest();
        var line = new com.aleksandarparipovic.marel_app.payroll_run_item.dto.AdjustmentPatchDto();
        line.setId(adjustmentId);
        line.setAmount(new java.math.BigDecimal(amount));
        patch.setAdjustments(java.util.List.of(line));
        payrollRunItemService.patch(itemId, patch);
    }

    private static java.math.BigDecimal total(java.util.Map<String, Object> detail) {
        return new java.math.BigDecimal(String.valueOf(summaryOf(detail).get("totalNetEarnings")));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> summaryOf(java.util.Map<String, Object> detail) {
        return (java.util.Map<String, Object>) detail.get("summary");
    }

    @SuppressWarnings("unchecked")
    private static java.util.stream.Stream<java.util.Map<String, Object>> lines(
            java.util.Map<String, Object> detail) {
        return ((List<java.util.Map<String, Object>>) detail.get("adjustments")).stream()
                .flatMap(s -> ((List<java.util.Map<String, Object>>) s.get("adjustments")).stream());
    }

    private static List<String> visibleCodes(java.util.Map<String, Object> detail) {
        return lines(detail).map(l -> String.valueOf(l.get("categoryCode"))).toList();
    }

    private static java.math.BigDecimal lineAmount(java.util.Map<String, Object> detail, String code) {
        return lines(detail)
                .filter(l -> code.equals(l.get("categoryCode")))
                .findFirst()
                .map(l -> new java.math.BigDecimal(String.valueOf(l.get("amount"))))
                .orElseThrow();
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
