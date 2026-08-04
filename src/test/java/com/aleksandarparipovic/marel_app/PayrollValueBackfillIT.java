package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 2 backfill, given a known input and read back.
 *
 * <p>Owed since Phase 2 and listed so it would not be forgotten. The migration
 * carries its own {@code DO $$} verification block, which fails it if any item
 * stops resolving to its own system rate — but that checks whatever rows happen
 * to be in the database it runs against. On a virgin schema that is nothing, so
 * the block would pass over a backfill that collapsed every period into one, or
 * lost the boundaries entirely.
 *
 * <p>WHAT THE BACKFILL DOES. {@code payroll_run_items} carries one rate per
 * month. The history carries one row per PERIOD, so consecutive months at the
 * same rate have to collapse into a single row that ends the day before the next
 * distinct rate begins — an inclusive {@code valid_until}, and the last row open.
 * Getting that wrong is not a cosmetic difference: the rate resolved for a month
 * is what prices every category row in it.
 *
 * <p>NOT {@code @Transactional}, because the script runs through psql on its own
 * connection and can only see committed rows. Everything it creates is scoped to
 * one employee and deleted afterwards — this suite shares a database, and a test
 * that leaves rows behind breaks a different one that nobody will connect to it.
 */
class PayrollValueBackfillIT extends AbstractIntegrationTest {

    private static final String SCRIPT = "2026-08-01-03-employee-payroll-value-backfill.sql";

    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate txTemplate;

    private Long employeeId;
    private Long departmentId;

    private void departmentId(Long id) { this.departmentId = id; }

    @AfterEach
    void removeWhatThisTestCommitted() {
        if (employeeId == null) {
            return;
        }
        txTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "DELETE FROM employee_payroll_value_history WHERE employee_id = :id")
                    .setParameter("id", employeeId).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM payroll_run_items WHERE employee_id = :id")
                    .setParameter("id", employeeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM employees WHERE id = :id")
                    .setParameter("id", employeeId).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM payroll_runs WHERE run_code LIKE 'IT-BACKFILL-%'")
                    .executeUpdate();
            if (departmentId != null) {
                entityManager.createNativeQuery("DELETE FROM departments WHERE id = :id")
                        .setParameter("id", departmentId).executeUpdate();
            }
        });
    }

    /** One employee, with a payroll item per month at the given rates from 2025-01. */
    private void seedMonthlyRates(String... rates) {
        employeeId = txTemplate.execute(status -> {
            Object departmentId = entityManager.createNativeQuery("""
                    INSERT INTO departments (name, created_at)
                    VALUES ('IT Backfill dept', now())
                    RETURNING id
                    """).getSingleResult();
            Object id = entityManager.createNativeQuery("""
                    INSERT INTO employees (department_id, full_name, employee_no,
                                           employment_start_date, is_foreigner, is_active,
                                           created_at, norm_grace_days,
                                           transport_allowance_mode, preferred_locale)
                    VALUES (:dept, 'IT Backfill', 'IT-BACKFILL-1', DATE '2024-01-01', FALSE,
                            TRUE, now(), 30, 'AUTO', 'sr-Latn')
                    RETURNING id
                    """).setParameter("dept", ((Number) departmentId).longValue()).getSingleResult();
            departmentId(((Number) departmentId).longValue());
            return ((Number) id).longValue();
        });

        for (int i = 0; i < rates.length; i++) {
            seedItem(LocalDate.of(2025, 1, 1).plusMonths(i), rates[i]);
        }
    }

    /**
     * One payroll item for one month.
     *
     * <p>A run of its own per month: {@code uq_payroll_run_items_employee} allows
     * an employee only one item per run, which is the real shape — a run IS a
     * month's payroll.
     */
    private void seedItem(LocalDate period, String rate) {
        txTemplate.executeWithoutResult(status -> {
            Object runId = entityManager.createNativeQuery("""
                    INSERT INTO payroll_runs (report_year, report_month, run_code, created_at)
                    VALUES (:year, :month, :code, now())
                    RETURNING id
                    """)
                    .setParameter("year", period.getYear())
                    .setParameter("month", period.getMonthValue())
                    .setParameter("code", "IT-BACKFILL-" + period)
                    .getSingleResult();

            entityManager.createNativeQuery("""
                    INSERT INTO payroll_run_items
                        (payroll_run_id, employee_id, period, status, currency_code,
                         hourly_rate, hourly_rate_system, hourly_rate_overridden, created_at)
                    VALUES (:run, :id, :period, 'DRAFT', 'RSD', CAST(:rate AS numeric),
                            CAST(:rate AS numeric), FALSE, now())
                    """)
                    .setParameter("run", ((Number) runId).longValue())
                    .setParameter("id", employeeId)
                    .setParameter("period", period)
                    .setParameter("rate", rate)
                    .executeUpdate();
        });
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> hourlyRatePeriods() {
        return entityManager.createNativeQuery("""
                SELECT h.valid_from, h.valid_until, h.numeric_value
                FROM employee_payroll_value_history h
                JOIN employee_payroll_value_definitions d ON d.id = h.value_definition_id
                WHERE h.employee_id = :id AND d.code = 'HOURLY_RATE'
                ORDER BY h.valid_from
                """)
                .setParameter("id", employeeId)
                .getResultList();
    }

    @Test
    @DisplayName("consecutive months at the same rate collapse into one period, closed the day before the next")
    void theCollapseProducesTheRightBoundaries() {
        // 300 · 300 · 400 · 400 · 400 · 350 across January to June.
        seedMonthlyRates("300.00", "300.00", "400.00", "400.00", "400.00", "350.00");

        runMigrationScript(SCRIPT);

        List<Object[]> periods = hourlyRatePeriods();

        assertThat(periods)
                .as("three rates, three periods — not six rows, and not one")
                .hasSize(3);

        // The boundary is what this test is for. valid_until is INCLUSIVE, so each
        // period ends the day BEFORE the next begins: an off-by-one here either
        // leaves a day with no rate in force or a day with two.
        assertThat((LocalDate) periods.get(0)[0]).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat((LocalDate) periods.get(0)[1]).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat((BigDecimal) periods.get(0)[2]).isEqualByComparingTo("300.00");

        assertThat((LocalDate) periods.get(1)[0]).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat((LocalDate) periods.get(1)[1]).isEqualTo(LocalDate.of(2025, 5, 31));
        assertThat((BigDecimal) periods.get(1)[2]).isEqualByComparingTo("400.00");

        assertThat((LocalDate) periods.get(2)[0]).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(periods.get(2)[1])
                .as("the last period stays open — the rate has not stopped applying")
                .isNull();
        assertThat((BigDecimal) periods.get(2)[2]).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("a rate that returns to an earlier value gets its own period, not the earlier one extended")
    void aReturningRateIsANewPeriod() {
        // 300 · 400 · 300. The middle rate breaks the run, so the two 300s are two
        // periods. Collapsing on the VALUE rather than on consecutive runs would
        // merge them and claim the employee was on 300 through April, which is
        // false and would reprice a month.
        seedMonthlyRates("300.00", "400.00", "300.00");

        runMigrationScript(SCRIPT);

        assertThat(hourlyRatePeriods()).hasSize(3);
        assertThat((LocalDate) hourlyRatePeriods().get(2)[0])
                .isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat((BigDecimal) hourlyRatePeriods().get(2)[2]).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("a gap in the months does not create a gap in the history")
    void aMissingMonthLeavesNoUncoveredDay() {
        // February has no payroll item at all — which is ordinary: nobody ran that
        // month. The period must still run from January to the next distinct rate,
        // or February resolves to no rate and the calculation refuses.
        seedMonthlyRates("300.00");
        seedItem(LocalDate.of(2025, 3, 1), "500.00");

        runMigrationScript(SCRIPT);

        List<Object[]> periods = hourlyRatePeriods();
        assertThat(periods).hasSize(2);
        assertThat((LocalDate) periods.get(0)[1])
                .as("January's period runs to the last day of February, so February has a rate")
                .isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    @DisplayName("re-running it changes nothing — the recovery path in a project with no migration runner")
    void reRunningIsANoOp() {
        seedMonthlyRates("300.00", "400.00");
        runMigrationScript(SCRIPT);
        List<Object[]> first = hourlyRatePeriods();

        runMigrationScript(SCRIPT);

        List<Object[]> second = hourlyRatePeriods();
        assertThat(second).hasSameSizeAs(first);
        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i)[0]).isEqualTo(first.get(i)[0]);
            assertThat(second.get(i)[1]).isEqualTo(first.get(i)[1]);
            assertThat((BigDecimal) second.get(i)[2]).isEqualByComparingTo((BigDecimal) first.get(i)[2]);
        }
    }
}
