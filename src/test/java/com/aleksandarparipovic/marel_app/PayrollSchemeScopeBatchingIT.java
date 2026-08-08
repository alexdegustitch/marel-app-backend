package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_run.PayrollRun;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import com.aleksandarparipovic.marel_app.work_category_resolution.IncompletePayrollConfigurationException;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScopeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A payroll run resolves compensation schemes ONCE, not once per employee.
 *
 * <p>Kept apart from {@link PayrollGoldenSnapshotIT} because the spy gives this
 * class its own application context, and because it asserts a different kind of
 * thing: the golden snapshot pins the numbers, this pins the cost of producing
 * them.
 *
 * <p>The regression it guards is easy to reintroduce and invisible in results:
 * {@code scopeFor(employee, from, to)} issues four queries — the two category
 * catalogues, the scheme periods, and the rules — so resolving it per row turns
 * one screen for a 300-person factory into more than a thousand queries that all
 * return one of a handful of answers.
 */
@Transactional
class PayrollSchemeScopeBatchingIT extends AbstractIntegrationTest {

    @Autowired private com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private PayrollRunItemService payrollRunItemService;

    @Autowired private EmployeeCompensationSchemeHistoryRepository schemeHistoryRepository;

    @MockitoSpyBean private PayrollSchemeScopeService scopeService;

    /** A line's figure, or zero when the scheme excludes the category. */
    private java.math.BigDecimal lineAmount(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getAmount()).orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineSystemAmount(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getSystemAmount()).orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineSystemUnit(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getSystemUnitAmount()).orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineUnit(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getUnitAmount() != null ? a.getUnitAmount() : a.getSystemUnitAmount())
                .orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineQuantity(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getSystemQuantity()).orElse(java.math.BigDecimal.ZERO);
    }

    @Test
    @DisplayName("one payroll run of five employees resolves the scheme scope once")
    void aRunResolvesScopesInOneBatch() {
        var first = fixture.scenario().build();
        PayrollRun run = first.payrollRun();
        for (int i = 0; i < 4; i++) {
            fixture.scenario().inRun(run).build();
        }

        Mockito.clearInvocations(scopeService);

        List<PayrollRunItem> items = payrollRunItemService.getForPayrollRun(run.getId());

        assertThat(items).hasSize(5);

        // Exactly one batched call for the whole run...
        Mockito.verify(scopeService, Mockito.times(1))
                .scopesFor(Mockito.<Collection<Long>>any(), Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class), Mockito.anyList(), Mockito.anyList());

        // ...and not a single per-employee one. This is the assertion that fails
        // the moment somebody calls scopeFor inside a loop again.
        Mockito.verify(scopeService, Mockito.never())
                .scopeFor(Mockito.anyLong(), Mockito.any(LocalDate.class), Mockito.any(LocalDate.class));
    }

    @Test
    @DisplayName("the batch covers every employee in the run, so the answers are unchanged")
    void batchingDoesNotChangeTheAnswer() {
        var standard = fixture.scenario().build();
        PayrollRun run = standard.payrollRun();
        var foreign = fixture.scenario()
                .inRun(run)
                .scheme(com.aleksandarparipovic.marel_app.compensation_scheme
                        .CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT)
                .denyAdjustment("MEAL_ALLOWANCE", "TRANSPORT_ALLOWANCE")
                .build();

        payrollRunItemService.getForPayrollRun(run.getId());

        PayrollRunItem standardItem = payrollRunItemService.findById(standard.item().getId());
        PayrollRunItem foreignItem = payrollRunItemService.findById(foreign.item().getId());

        // Two employees, two different schemes, resolved in one batch — the
        // restricted one must still come out restricted.
        assertThat(lineAmount(standardItem, "MEAL_ALLOWANCE")).isEqualByComparingTo("6000.00");
        assertThat(lineAmount(foreignItem, "MEAL_ALLOWANCE")).isEqualByComparingTo("0.00");
        assertThat(lineAmount(foreignItem, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a run whose items carry no period falls back to per-item resolution")
    void noPeriodFallsBackInsteadOfSilentlyGoingUnrestricted() {
        var scenario = fixture.scenario()
                .scheme(com.aleksandarparipovic.marel_app.compensation_scheme
                        .CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT)
                .denyAdjustment("MEAL_ALLOWANCE")
                .build();

        // No period means nothing to batch on. The dangerous outcome would be an
        // empty batch read as "no restriction", which would start paying a meal
        // allowance the scheme excludes. Falling back to per-item resolution keeps
        // the exclusion; the item's own monthly report still knows the period.
        PayrollRunItem item = payrollRunItemService.findById(scenario.item().getId());
        item.setPeriod(null);

        payrollRunItemService.getForPayrollRun(scenario.payrollRun().getId());

        assertThat(lineAmount(payrollRunItemService.findById(scenario.item().getId()), "MEAL_ALLOWANCE"))
                .as("falling back to per-item resolution keeps the exclusion")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an employee with no scheme stops the whole run rather than being paid unrestricted")
    void anEmployeeWithoutASchemeStopsTheRun() {
        var scenario = fixture.scenario().build();

        // Archive the only scheme period, leaving the employee with none.
        schemeHistoryRepository.findHistoryFor(scenario.employee().getId())
                .forEach(p -> {
                    p.setArchivedAt(java.time.OffsetDateTime.now());
                    schemeHistoryRepository.save(p);
                });
        schemeHistoryRepository.flush();

        // Loud, and it names the employee. The alternative — one row quietly
        // calculated under no policy — is the failure nobody finds until payday.
        assertThatThrownBy(() -> payrollRunItemService.getForPayrollRun(scenario.payrollRun().getId()))
                .isInstanceOf(IncompletePayrollConfigurationException.class)
                .hasMessageContaining(String.valueOf(scenario.employee().getId()));
    }
}
