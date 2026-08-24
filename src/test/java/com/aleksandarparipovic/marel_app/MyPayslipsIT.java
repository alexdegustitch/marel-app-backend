package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_field_access.PayrollFieldAccessService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A worker's own payslip.
 *
 * <p>Everywhere else in this system, "may I see what a payroll is worth" is
 * answered by role: {@code PayrollVisibilityPolicy} removes the amounts for
 * everyone but payroll, and {@code PayrollFieldAccessService} narrows what is
 * left, line by line. That is the right answer for somebody reading ANOTHER
 * person's month.
 *
 * <p>It is the wrong answer for the one document a person is entitled to in
 * full — their own payslip, the paper payroll hands them. So there is one
 * exception, and this is where it is pinned down: full figures, and only for
 * oneself, and only once the month is finished.
 *
 * <p>What must stay true, and is asserted below:
 * <ul>
 *   <li>the exception is by IDENTITY, not by role — a supervisor who may see
 *       nothing on anyone else's payroll sees everything on their own;
 *   <li>it does not widen by one row: another worker's month is refused to the
 *       same caller, in the same breath;
 *   <li>it reaches only LOCKED months, so nobody is handed a figure that can
 *       still move;
 *   <li>it grants no writes. It is a document, not a screen with actions on it.
 * </ul>
 */
@Transactional
class MyPayslipsIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollFieldAccessService fieldAccessService;
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

    /** DRAFT → APPROVED → LOCKED, the only way a month becomes a payslip. */
    private void finish(Long itemId) {
        payrollRunItemService.submit(itemId, null);
        payrollRunItemService.lock(itemId);
    }

    @Test
    @DisplayName("only finished months are offered — a draft is not a payslip")
    void onlyLockedMonthsAreListed() {
        var open = fixture.scenario().build();

        assertThat(payrollRunItemService.lockedPayrollsOf(open.employee().getId())).isEmpty();

        finish(open.item().getId());

        var offered = payrollRunItemService.lockedPayrollsOf(open.employee().getId());
        assertThat(offered).hasSize(1);
        assertThat(offered.getFirst().monthlyReportId()).isEqualTo(open.monthlyReport().getId());
        assertThat(offered.getFirst().lockedAt()).isNotNull();
    }

    /**
     * THE POINT OF THE WHOLE CHANGE.
     *
     * <p>The same reader, the same instant, two payrolls: on somebody else's the
     * role rules apply and there is nothing to read; on their own the document
     * arrives whole. Asserting both in one test is deliberate — it is the pair
     * that is the rule, and either half alone would pass for the wrong reason.
     */
    @Test
    @DisplayName("a restricted role sees their own payslip in full, and no one else's")
    void ownPayslipIsCompleteAndNobodyElsesIs() {
        var mine = fixture.scenario().build();
        var theirs = fixture.scenario().build();
        finish(mine.item().getId());
        finish(theirs.item().getId());

        signedInAs("supervisor");

        // Nothing granted, so the ordinary path gives this reader a payroll with
        // its money removed — the behaviour every other screen depends on.
        var throughTheUsualDoor = payrollRunItemService.getDetails(mine.monthlyReport().getId());
        assertThat(throughTheUsualDoor.getSummary().getNetPayableAmount()).isNull();
        assertThat(throughTheUsualDoor.isPartialView()).isTrue();
        assertThat(fieldAccessService.accessTo("MEAL_ALLOWANCE").canView()).isFalse();

        // Their own, and it is whole.
        var ownPayslip = payrollRunItemService.ownDetails(
                mine.monthlyReport().getId(), mine.employee().getId(), null);
        assertThat(ownPayslip.getSummary().getNetPayableAmount()).isNotNull();
        assertThat(ownPayslip.getSummary().getTotalNetEarnings()).isNotNull();
        assertThat(ownPayslip.isPartialView()).isFalse();
        assertThat(ownPayslip.getAdjustments()).isNotEmpty();
        assertThat(ownPayslip.getAdjustments().stream()
                .flatMap(section -> section.getAdjustments().stream()))
                .isNotEmpty()
                .allMatch(line -> !line.isValueHidden());

        // And the exception did not widen by one row: the colleague's month is
        // refused to the very same caller.
        assertThatThrownBy(() -> payrollRunItemService.ownDetails(
                theirs.monthlyReport().getId(), mine.employee().getId(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an unfinished month is refused even to the person it is about")
    void anOpenMonthIsNotYetTheirs() {
        var scenario = fixture.scenario().build();
        Long monthlyReportId = scenario.monthlyReport().getId();
        Long employeeId = scenario.employee().getId();

        signedInAs("supervisor");

        // DRAFT — still being prepared.
        assertThatThrownBy(() -> payrollRunItemService.ownDetails(monthlyReportId, employeeId, null))
                .isInstanceOf(AccessDeniedException.class);

        // APPROVED — handed over, but payroll has not had its last word. Handing
        // it over now would publish a figure that can still be corrected.
        payrollRunItemService.submit(scenario.item().getId(), null);
        assertThatThrownBy(() -> payrollRunItemService.ownDetails(monthlyReportId, employeeId, null))
                .isInstanceOf(AccessDeniedException.class);

        payrollRunItemService.lock(scenario.item().getId());
        assertThat(payrollRunItemService.ownDetails(monthlyReportId, employeeId, null)).isNotNull();
    }

    /**
     * The refusal must not double as a way to find out what exists. Both the
     * wrong owner and the unfinished month answer with the same sentence, so
     * nothing can be learned by trying ids.
     */
    @Test
    @DisplayName("a month that does not exist is refused the same way as one that is not yours")
    void refusalTellsNothingApart() {
        var scenario = fixture.scenario().build();
        finish(scenario.item().getId());
        signedInAs("supervisor");

        String forSomebodyElse = catchMessage(() -> payrollRunItemService.ownDetails(
                scenario.monthlyReport().getId(), scenario.employee().getId() + 9_999, null));
        String forNothingAtAll = catchMessage(() -> payrollRunItemService.ownDetails(
                999_999_999L, scenario.employee().getId(), null));

        assertThat(forSomebodyElse).isEqualTo(forNothingAtAll);
    }

    @Test
    @DisplayName("one's own payslip carries no actions")
    void ownPayslipIsReadOnly() {
        var scenario = fixture.scenario().build();
        finish(scenario.item().getId());

        // As an ADMIN, who would ordinarily get every permission answered yes.
        signedInAs("admin");
        var payslip = payrollRunItemService.ownDetails(
                scenario.monthlyReport().getId(), scenario.employee().getId(), null);

        var permissions = payslip.getPermissions();
        assertThat(permissions.isCanEditAdjustments()).isFalse();
        assertThat(permissions.isCanLock()).isFalse();
        assertThat(permissions.isCanApprove()).isFalse();
        assertThat(permissions.isCanEditHourlyRate()).isFalse();
        assertThat(permissions.isCanEditTotalNetEarnings()).isFalse();
    }

    private String catchMessage(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected the call to be refused");
        } catch (AccessDeniedException expected) {
            return expected.getMessage();
        }
    }
}
