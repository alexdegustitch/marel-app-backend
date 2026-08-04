package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_maintenance.PayrollMaintenanceService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep that pushes every payroll item through the calculator once.
 *
 * <p>Recalculation is lazy — an item is recomputed when somebody opens it. After
 * moving the override state onto the lines, most items still held what the old
 * code left behind, and the diagnostic comparing column against line had nothing
 * to compare: 3162 of 3177 lines had never been calculated at all.
 *
 * <p>@Transactional, and that is a compromise worth naming. The sweep gives each
 * item its own transaction in production, which a test transaction wrapping the
 * whole run cannot show. Without it, though, this test leaves its scenarios in the
 * shared database — the first version deleted a scheme's rules and left an app
 * setting in force, and two unrelated tests failed because of it. What is verified
 * here is that the sweep visits everything, calculates what it visits, skips
 * LOCKED items and keeps going after a failure it reports; per-transaction
 * isolation is left to production and to reading recalculateAll.
 */
@org.springframework.transaction.annotation.Transactional
class PayrollMaintenanceSweepIT extends AbstractIntegrationTest {

    @Autowired private PayrollMaintenanceService maintenanceService;
    @Autowired private PayrollRunItemRepository itemRepository;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private PayrollScenarioFixture fixture;
    /**
     * Setup goes through JDBC, not the EntityManager: this test is deliberately
     * not @Transactional — the sweep gives each item its own transaction and a
     * test-wide one would hide exactly that — so there is no transaction for a
     * native query to join.
     */
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("the sweep calculates a line that had never been through the calculator")
    void theSweepCalculatesAnUntouchedLine() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        // The state the dev database was in: a line that exists but has never been
        // computed, so calculated_at is null and nothing can be compared against it.
        jdbc.update("UPDATE payroll_adjustments SET calculated_at = NULL WHERE payroll_run_item_id = ?", itemId);

        var report = maintenanceService.recalculateAll();

        assertThat(report.visited()).isPositive();
        assertThat(report.recalculated()).isPositive();
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(itemId, "MEAL_ALLOWANCE").orElseThrow()
                .getCalculatedAt())
                .as("the sweep must actually calculate, not merely visit")
                .isNotNull();
    }

    @Test
    @DisplayName("it leaves LOCKED items alone")
    void lockedItemsAreNotTouched() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        jdbc.update("UPDATE payroll_run_items SET status = 'LOCKED' WHERE id = ?", itemId);

        maintenanceService.recalculateAll();

        // A locked item is an immutable snapshot. Flagging it would also leave
        // needs_recalculation set on a row nothing will ever clear.
        var locked = itemRepository.findById(itemId).orElseThrow();
        assertThat(locked.getNeedsRecalculation()).isFalse();
    }

    @Test
    @DisplayName("one broken item does not stop the rest, and the report names it")
    void oneFailureDoesNotStopTheSweep() {
        var healthy = fixture.scenario().build();
        var broken = fixture.scenario().build();

        // An employee with NO compensation scheme in force. D1 makes that an
        // error rather than a silent default, so the recalculation throws — and it
        // is scoped to this one employee, unlike deleting a scheme's rules, which
        // the first version of this test did and broke two unrelated tests with.
        jdbc.update("DELETE FROM employee_compensation_scheme_history WHERE employee_id = ?",
                broken.employee().getId());

        var report = maintenanceService.recalculateAll();

        assertThat(report.failed()).isPositive();
        assertThat(report.failures()).isNotEmpty();
        assertThat(report.visited()).isEqualTo(report.recalculated() + report.failed());
        // The healthy one still went through — the whole point of per-item
        // transactions rather than one sweep-wide rollback.
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(healthy.item().getId(), "MEAL_ALLOWANCE").orElseThrow()
                .getCalculatedAt()).isNotNull();
    }
}
