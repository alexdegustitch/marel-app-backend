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

        // THE LINES ARE ALL STILL THERE. Somebody should be able to see that a
        // bonus exists on their payroll without being shown what it is worth,
        // and a screen missing rows is a different document, not a safer one.
        int shownLineCount = filtered.getAdjustments().stream()
                .mapToInt(section -> section.getAdjustments().size()).sum();
        assertThat(shownLineCount).isEqualTo(fullLineCount);

        // What went is the figures, and only on the lines not granted.
        var lines = filtered.getAdjustments().stream()
                .flatMap(section -> section.getAdjustments().stream())
                .collect(java.util.stream.Collectors.toMap(a -> a.getCategoryCode(), a -> a, (x, y) -> x));

        assertThat(lines.get("MEAL_ALLOWANCE").isValueHidden()).isFalse();
        assertThat(lines.get("OTHER").isValueHidden()).isTrue();
        assertThat(lines.get("OTHER").getAmount()).isNull();
        assertThat(lines.get("OTHER").getUnitAmount()).isNull();
        assertThat(lines.get("OTHER").getCalculationInputs()).isNull();
        // The name is not the secret.
        assertThat(lines.get("OTHER").getCategoryDisplayName()).isNotBlank();

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

        // Every line is still listed, and the ones not theirs carry no figures.
        // Freezing is not a way around the filter, in either direction.
        assertThat(visibleCodes(frozen.get())).contains("OTHER", "MEAL_ALLOWANCE");
        assertThat(lineOf(frozen.get(), "MEAL_ALLOWANCE").get("amount")).isNull();
        assertThat(lineOf(frozen.get(), "MEAL_ALLOWANCE").get("valueHidden")).isEqualTo(true);

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

    @Test
    @DisplayName("a supervisor hands over the whole payroll, not the half they can see")
    void snapshotRecordsEverything() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        signedInAs("admin");
        service.set("OTHER", "supervisor", true, true);

        // Submitted BY the restricted reader — the case where the record could
        // silently shrink to whatever their own screen showed.
        signedInAs("supervisor");
        payrollRunItemService.submit(itemId, "predato");

        signedInAs("admin");
        Long handoverId = payrollRunItemService.getHandovers(itemId).get(0).id();
        var snapshot = payrollRunItemService.getHandoverSnapshot(handoverId).orElseThrow();

        // A line the submitter cannot see is in the record all the same.
        assertThat(visibleCodes(snapshot)).contains("MEAL_ALLOWANCE", "OTHER");
    }

    /*
     * The third write rule: a line somebody may READ and must not CHANGE. The
     * other two need no configuration — what you cannot see you cannot write,
     * and a handed-over payroll is closed — so this is the only one the settings
     * screen has to say out loud.
     */
    @Test
    @DisplayName("a line granted for reading only arrives locked and refuses the write")
    void seeingIsNotEditing() {
        var scenario = fixture.scenario().build();
        Long monthlyReportId = scenario.monthlyReport().getId();
        Long itemId = scenario.item().getId();
        Long otherId = scenario.adjustment("OTHER").getId();

        signedInAs("admin");
        service.set("OTHER", "supervisor", true, false);

        signedInAs("supervisor");

        // The screen is told, so nobody types into a field that cannot save.
        var line = payrollRunItemService.getDetails(monthlyReportId).getAdjustments().stream()
                .flatMap(s -> s.getAdjustments().stream())
                .filter(a -> "OTHER".equals(a.getCategoryCode()))
                .findFirst().orElseThrow();
        assertThat(line.getEditableInput()).isEqualTo("NONE");
        assertThat(line.getAllowTotalOverride()).isFalse();

        // And the server refuses it anyway, loudly. A silent success would be
        // the worst of the three answers.
        assertThatThrownBy(() -> setAmount(itemId, otherId, "4321.00"))
                .hasMessageContaining("OTHER");

        // Granted for editing, the same line goes through.
        signedInAs("admin");
        service.set("OTHER", "supervisor", true, true);
        signedInAs("supervisor");
        setAmount(itemId, otherId, "4321.00");

        signedInAs("admin");
        assertThat(payrollRunItemService.getDetails(monthlyReportId).getAdjustments().stream()
                .flatMap(s -> s.getAdjustments().stream())
                .filter(a -> "OTHER".equals(a.getCategoryCode()))
                .findFirst().orElseThrow().getAmount())
                .isEqualByComparingTo("4321.00");
    }

    @Test
    @DisplayName("the two item-level figures follow the same rule as a line")
    void itemLevelFiguresAreGatedToo() throws Exception {
        var scenario = fixture.scenario().build();
        Long monthlyReportId = scenario.monthlyReport().getId();
        Long itemId = scenario.item().getId();

        signedInAs("admin");
        // Readable, and not editable. The case sloj C exists for.
        service.set(PayrollFieldAccessService.FIELD_HOURLY_RATE, "supervisor", true, false);
        service.set(PayrollFieldAccessService.FIELD_TOTAL_NET_EARNINGS, "supervisor", true, false);

        signedInAs("supervisor");
        var permissions = payrollRunItemService.getDetails(monthlyReportId).getPermissions();
        assertThat(permissions.isCanEditHourlyRate()).isFalse();
        assertThat(permissions.isCanEditTotalNetEarnings()).isFalse();

        // The item-level total is patchable in its own right — refusing it on the
        // lines would have left this door open while the other was watched.
        var patch = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
                .readValue("{\"totalNetEarnings\":1.00}",
                        com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest.class);
        assertThatThrownBy(() -> payrollRunItemService.patch(itemId, patch))
                .hasMessageContaining("ukupnu zaradu");

        // Payroll is not asked.
        signedInAs("admin");
        assertThat(payrollRunItemService.getDetails(monthlyReportId).getPermissions()
                .isCanEditTotalNetEarnings()).isTrue();
    }

    @Test
    @DisplayName("what the screen is told it may do matches what the server allows")
    void permissionsAreAnsweredByCapability() {
        var scenario = fixture.scenario().build();
        Long monthlyReportId = scenario.monthlyReport().getId();

        signedInAs("admin");
        var forPayroll = payrollRunItemService.getDetails(monthlyReportId).getPermissions();
        // These were all false for everybody, always: the check compared
        // authorities against ROLE_ADMIN while they are issued lowercase.
        assertThat(forPayroll.isCanLock()).isTrue();
        assertThat(forPayroll.isCanApprove()).isTrue();
        assertThat(forPayroll.isCanEditAdjustments()).isTrue();

        signedInAs("supervisor");
        var forSupervisor = payrollRunItemService.getDetails(monthlyReportId).getPermissions();
        // May hand over, may not lock — and with nothing granted, has no line to
        // change, so the screen is not put into edit mode over an empty payroll.
        assertThat(forSupervisor.isCanApprove()).isTrue();
        assertThat(forSupervisor.isCanLock()).isFalse();
        assertThat(forSupervisor.isCanEditAdjustments()).isFalse();

        signedInAs("admin");
        service.set("OTHER", "supervisor", true, true);
        signedInAs("supervisor");
        assertThat(payrollRunItemService.getDetails(monthlyReportId).getPermissions()
                .isCanEditAdjustments()).isTrue();
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

    private static java.util.Map<String, Object> lineOf(
            java.util.Map<String, Object> detail, String code) {
        return lines(detail)
                .filter(l -> code.equals(l.get("categoryCode")))
                .findFirst().orElseThrow();
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
