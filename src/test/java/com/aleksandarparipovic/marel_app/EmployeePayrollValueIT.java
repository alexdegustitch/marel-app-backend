package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueDefinition;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueDefinitionRepository;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueHistory;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueHistoryRepository;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-employee payroll values: the history, its constraints, and the one
 * operation that is allowed to change a value.
 *
 * <p>What is being protected is that <b>a value is never edited in place</b>. Any
 * path that rewrites a period instead of closing it and opening a new one
 * silently reprices every month already calculated under the old value — which is
 * the exact defect this table was introduced to close.
 */
@Transactional
class EmployeePayrollValueIT extends AbstractIntegrationTest {

    @Autowired private EmployeePayrollValueService valueService;
    @Autowired private EmployeePayrollValueHistoryRepository historyRepository;
    @Autowired private EmployeePayrollValueDefinitionRepository definitionRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;
    @Autowired private com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository payrollRunItemRepository;

    private Employee anEmployee() {
        return fixture.scenario().build().employee();
    }

    /**
     * An employee with no transport entitlement of any kind.
     *
     * <p>The ordinary scenario grants TRANSPORT_PER_DAY from 2020, because that is
     * what every employee effectively had before OPEN-15 gave the mode a start
     * date. The tests below are about granting and withdrawing it, so they need
     * somebody who has not been given it already.
     */
    private Employee anEmployeeWithNoTransport() {
        return fixture.scenario().withoutTransportEntitlement().build().employee();
    }

    private EmployeePayrollValueDefinition definition(String code) {
        return definitionRepository.findByCode(code).orElseThrow();
    }

    // ── the seeded catalogue ────────────────────────────────────────────────

    @Test
    @DisplayName("the four system definitions are seeded, and only HOURLY_RATE has no payslip line")
    void definitionsAreSeeded() {
        assertThat(valueService.definitions())
                .extracting(EmployeePayrollValueDefinition::getCode)
                .contains("HOURLY_RATE", "TRANSPORT_FIXED_MONTHLY", "FIXED_LD_AMOUNT",
                        "TELEPHONE_AMOUNT");

        // HOURLY_RATE prices work categories; it is an input, not a line, so it is
        // the one definition that must never acquire an adjustment category. The
        // others do get one in production, but the test schema applies migrations
        // only from 2026-07-21 and the category catalogue is seeded below that
        // cutoff, so the link is repaired by re-running the migration rather than
        // being present here.
        assertThat(definition(EmployeePayrollValueCodes.HOURLY_RATE)
                .getPayrollAdjustmentCategory()).isNull();

        assertThat(definition(EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY).getUnitCode())
                .isEqualTo("RSD");

        // BONUS_PERCENTAGE was seeded by 2026-08-01-01 and removed by 2026-08-29-01.
        // The bonus is a flat amount from the employee's bonus category, resolved
        // for the period through employees_bonus_history — nothing here multiplies
        // by a per-employee percentage, and no value was ever written for it.
        assertThat(valueService.definitions())
                .extracting(EmployeePayrollValueDefinition::getCode)
                .doesNotContain("BONUS_PERCENTAGE");
        assertThat(definition(EmployeePayrollValueCodes.HOURLY_RATE).getValueType())
                .isEqualTo("NUMERIC");
        assertThat(definition(EmployeePayrollValueCodes.HOURLY_RATE).getIsSystem()).isTrue();
    }

    // ── close-then-open ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a change closes the old period the day before and opens the new one")
    void changeClosesThenOpens() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 1, 1), "initial", null);

        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 1), "raise", null);

        List<EmployeePayrollValueHistory> history =
                valueService.getHistory(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE);

        assertThat(history).hasSize(2);

        // INCLUSIVE valid_until: the old period's last day is 31 August, so the two
        // touch without overlapping and 1 September already belongs to the new one.
        EmployeePayrollValueHistory older = history.get(1);
        assertThat(older.getNumericValue()).isEqualByComparingTo("400.00");
        assertThat(older.getValidUntil()).isEqualTo(LocalDate.of(2026, 8, 31));

        EmployeePayrollValueHistory newer = history.get(0);
        assertThat(newer.getNumericValue()).isEqualByComparingTo("450.00");
        assertThat(newer.getValidFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(newer.getValidUntil()).isNull();
    }

    @Test
    @DisplayName("the old value still resolves for a date inside the old period")
    void anOldMonthKeepsTheOldValue() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 1, 1), null, null);
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 1), null, null);

        // This is the whole point of the table.
        assertThat(rate(employee, LocalDate.of(2026, 3, 1))).isEqualByComparingTo("400.00");
        assertThat(rate(employee, LocalDate.of(2026, 8, 31))).isEqualByComparingTo("400.00");
        assertThat(rate(employee, LocalDate.of(2026, 9, 1))).isEqualByComparingTo("450.00");
        assertThat(rate(employee, LocalDate.of(2026, 12, 1))).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("a date before the first period resolves to nothing, not to zero")
    void beforeTheFirstPeriodThereIsNoValue() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 1, 1), null, null);

        // "Not configured" and "zero" are different answers, and a calculator that
        // conflates them pays somebody nothing without saying so.
        assertThat(valueService.numericValueOn(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, LocalDate.of(2025, 12, 31)))
                .isEmpty();
    }

    // ── what the service refuses ────────────────────────────────────────────

    @Test
    @DisplayName("a value inserted before an existing future period stops the day before it")
    void insertingBeforeAFuturePeriodBoundsTheNewOne() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 1, 1), null, null);
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("500.00"), LocalDate.of(2026, 12, 1), null, null);

        // A value from September, with December already scheduled. The December
        // decision is neither deleted nor overrun: the new period simply stops the
        // day before it starts.
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 1), null, null);

        assertThat(rate(employee, LocalDate.of(2026, 8, 31))).isEqualByComparingTo("400.00");
        assertThat(rate(employee, LocalDate.of(2026, 9, 1))).isEqualByComparingTo("450.00");
        assertThat(rate(employee, LocalDate.of(2026, 11, 30))).isEqualByComparingTo("450.00");
        assertThat(rate(employee, LocalDate.of(2026, 12, 1))).isEqualByComparingTo("500.00");

        assertThat(valueService.getHistory(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE)).hasSize(3);
    }

    @Test
    @DisplayName("a value starting exactly where one already starts is refused")
    void theSameStartDateIsRefused() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 1, 1), null, null);

        // Replacing a period in place is the one thing this service must never do,
        // so it refuses rather than guessing what was meant.
        assertThatThrownBy(() -> valueService.changeValue(
                employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("450.00"), LocalDate.of(2026, 1, 1), null, null))
                .isInstanceOf(ConflictException.class);
    }

    // ── backdating: the answer to OPEN-7 ────────────────────────────────────

    @Test
    @DisplayName("a rate can be backdated before the whole history — 'actually from January 2025'")
    void backdatingBeforeTheWholeHistory() {
        Employee employee = anEmployee();

        // What the phase 2 backfill produces: a transport rate starting from the
        // first month not yet calculated, because the real date is not recorded.
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("350.00"), LocalDate.of(2026, 8, 1), "backfill", null);

        // The administrator knows this employee's transport really began earlier,
        // at a different rate.
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("300.00"), LocalDate.of(2025, 1, 1), "actually from January 2025", null);

        assertThat(transportRate(employee, LocalDate.of(2025, 6, 1))).isEqualByComparingTo("300.00");
        assertThat(transportRate(employee, LocalDate.of(2026, 7, 31))).isEqualByComparingTo("300.00");
        assertThat(transportRate(employee, LocalDate.of(2026, 8, 1))).isEqualByComparingTo("350.00");

        // Still nothing before the correction — backdating states when it started,
        // it does not claim it always applied.
        assertThat(valueService.numericValueOn(employee.getId(),
                EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY, LocalDate.of(2024, 12, 31)))
                .isEmpty();
    }

    @Test
    @DisplayName("backdating the SAME value extends the period instead of splitting it")
    void backdatingTheSameValueExtends() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("350.00"), LocalDate.of(2026, 8, 1), "backfill", null);

        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("350.00"), LocalDate.of(2025, 1, 1), "actually from January 2025", null);

        // One period, not two. Two adjacent periods holding the same number say
        // nothing a single one does not, and the split would misrepresent a
        // correction as a change of rate.
        assertThat(valueService.getHistory(employee.getId(),
                EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY)).hasSize(1);
        assertThat(transportRate(employee, LocalDate.of(2025, 1, 1))).isEqualByComparingTo("350.00");
        assertThat(transportRate(employee, LocalDate.of(2026, 9, 1))).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("setting the same value again is refused rather than creating a no-op period")
    void theSameValueIsRefused() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 1, 1), null, null);

        assertThatThrownBy(() -> valueService.changeValue(
                employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 9, 1), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an unregistered code is refused — a calculator cannot invent a key")
    void anUnknownCodeIsRefused() {
        Employee employee = anEmployee();

        assertThatThrownBy(() -> valueService.changeValue(
                employee.getId(), "MY_NEW_MAGIC_VALUE",
                new BigDecimal("1.00"), LocalDate.of(2026, 1, 1), null, null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("MY_NEW_MAGIC_VALUE");
    }

    @Test
    @DisplayName("a negative value is refused")
    void negativeIsRefused() {
        Employee employee = anEmployee();

        assertThatThrownBy(() -> valueService.changeValue(
                employee.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("-1.00"), LocalDate.of(2026, 1, 1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── what the DATABASE refuses, whatever the service does ────────────────

    @Test
    @DisplayName("overlapping periods are rejected by the exclusion constraint")
    void overlapIsRejectedByTheDatabase() {
        Employee employee = anEmployee();
        EmployeePayrollValueDefinition hourly = definition(EmployeePayrollValueCodes.HOURLY_RATE);

        historyRepository.saveAndFlush(row(employee, hourly, "400.00",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)));

        // The service is not the guarantee: two concurrent requests can both pass
        // its check and then both insert. This is the guarantee.
        assertThatThrownBy(() -> historyRepository.saveAndFlush(row(employee, hourly, "450.00",
                LocalDate.of(2026, 6, 1), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("periods that merely touch are allowed — valid_until is inclusive")
    void touchingPeriodsAreAllowed() {
        Employee employee = anEmployee();
        EmployeePayrollValueDefinition hourly = definition(EmployeePayrollValueCodes.HOURLY_RATE);

        historyRepository.saveAndFlush(row(employee, hourly, "400.00",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31)));
        historyRepository.saveAndFlush(row(employee, hourly, "450.00",
                LocalDate.of(2026, 9, 1), null));

        // If the constraint used valid_until as exclusive this pair would leave
        // 31 August uncovered, and the wrong day would silently have no rate.
        assertThat(rate(employee, LocalDate.of(2026, 8, 31))).isEqualByComparingTo("400.00");
        assertThat(rate(employee, LocalDate.of(2026, 9, 1))).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("valid_until before valid_from is rejected")
    void invertedPeriodIsRejected() {
        Employee employee = anEmployee();
        EmployeePayrollValueDefinition hourly = definition(EmployeePayrollValueCodes.HOURLY_RATE);

        assertThatThrownBy(() -> historyRepository.saveAndFlush(row(employee, hourly, "400.00",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a value column that does not match the declared type is rejected")
    void valueMustMatchItsDeclaredType() {
        Employee employee = anEmployee();
        EmployeePayrollValueDefinition hourly = definition(EmployeePayrollValueCodes.HOURLY_RATE);

        EmployeePayrollValueHistory wrong = EmployeePayrollValueHistory.builder()
                .employee(employee)
                .definition(hourly)
                .valueType("NUMERIC")
                .textValue("four hundred")     // populated instead of numeric_value
                .validFrom(LocalDate.of(2026, 1, 1))
                .build();

        assertThatThrownBy(() -> historyRepository.saveAndFlush(wrong))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a value_type the definition does not declare is rejected by the composite key")
    void valueTypeCannotDriftFromItsDefinition() {
        Employee employee = anEmployee();
        EmployeePayrollValueDefinition hourly = definition(EmployeePayrollValueCodes.HOURLY_RATE);

        EmployeePayrollValueHistory drifted = EmployeePayrollValueHistory.builder()
                .employee(employee)
                .definition(hourly)
                .valueType("TEXT")             // the definition says NUMERIC
                .textValue("four hundred")
                .validFrom(LocalDate.of(2026, 1, 1))
                .build();

        // fk_epvh_definition_type references (id, value_type), so the pair has to
        // exist on the definition. No trigger keeps this in sync — the key does.
        assertThatThrownBy(() -> historyRepository.saveAndFlush(drifted))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── batch lookup ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the batch lookup answers many employees in one query")
    void batchLookup() {
        Employee a = anEmployee();
        Employee b = anEmployee();
        Employee withoutAValue = anEmployee();

        valueService.changeValue(a.getId(), EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2026, 1, 1), null, null);
        valueService.changeValue(b.getId(), EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("350.00"), LocalDate.of(2026, 1, 1), null, null);

        var values = valueService.numericValuesOn(
                List.of(a.getId(), b.getId(), withoutAValue.getId()), LocalDate.of(2026, 9, 1));

        // Compared by value, not by equals: BigDecimal.equals is scale-sensitive and
        // the column is numeric(38,6), so 400.00 comes back as 400.000000.
        assertThat(values.get(a.getId()).get(EmployeePayrollValueCodes.HOURLY_RATE))
                .isEqualByComparingTo("400.00");
        assertThat(values.get(b.getId()).get(EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY))
                .isEqualByComparingTo("350.00");

        // Absent, not present-and-empty: the caller must decide what "no value"
        // means for its own calculation.
        assertThat(values).doesNotContainKey(withoutAValue.getId());
    }

    // ── BOOLEAN values: an entitlement with a start date ────────────────────

    @Test
    @DisplayName("a flag granted from a date is not in force the day before")
    void aFlagStartsOnItsDate() {
        Employee employee = anEmployeeWithNoTransport();

        valueService.changeFlag(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_PER_DAY,
                true, LocalDate.of(2026, 9, 1), null, null);

        assertThat(valueService.trueFlagsOn(List.of(employee.getId()), LocalDate.of(2026, 9, 1)))
                .containsEntry(employee.getId(),
                        java.util.Set.of(EmployeePayrollValueCodes.TRANSPORT_PER_DAY));

        // The whole reason this value exists: the month before it pays nothing.
        assertThat(valueService.trueFlagsOn(List.of(employee.getId()), LocalDate.of(2026, 8, 31)))
                .doesNotContainKey(employee.getId());
    }

    @Test
    @DisplayName("withdrawing it is a FALSE period, not a deletion — the earlier months keep it")
    void withdrawingAFlagLeavesTheEarlierPeriodIntact() {
        Employee employee = anEmployeeWithNoTransport();
        valueService.changeFlag(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_PER_DAY,
                true, LocalDate.of(2026, 1, 1), null, null);

        valueService.changeFlag(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_PER_DAY,
                false, LocalDate.of(2026, 9, 1), "Prešao na fiksni", null);

        assertThat(valueService.trueFlagsOn(List.of(employee.getId()), LocalDate.of(2026, 8, 1)))
                .as("a month already calculated must not lose its transport")
                .containsKey(employee.getId());
        assertThat(valueService.trueFlagsOn(List.of(employee.getId()), LocalDate.of(2026, 9, 1)))
                .doesNotContainKey(employee.getId());
    }

    @Test
    @DisplayName("a boolean value cannot be written to a numeric definition, or the reverse")
    void theDeclaredTypeIsEnforced() {
        Employee employee = anEmployee();

        assertThatThrownBy(() -> valueService.changeFlag(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, true, LocalDate.of(2026, 1, 1), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.TRANSPORT_PER_DAY, new BigDecimal("1.00"),
                LocalDate.of(2026, 1, 1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a flag never appears among the numeric values")
    void flagsStayOutOfTheNumericMap() {
        Employee employee = anEmployeeWithNoTransport();
        valueService.changeFlag(employee.getId(), EmployeePayrollValueCodes.TRANSPORT_PER_DAY,
                true, LocalDate.of(2026, 1, 1), null, null);

        // "Absent means not configured" is the contract every calculator reads the
        // numeric map by. A boolean row landing in it mapped the code to null and,
        // worse, made an employee with no numeric value at all appear present.
        assertThat(valueService.numericValuesOn(List.of(employee.getId()), LocalDate.of(2026, 9, 1)))
                .doesNotContainKey(employee.getId());
    }

    private BigDecimal transportRate(Employee employee, LocalDate on) {
        return valueService.numericValueOn(employee.getId(),
                        EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY, on)
                .orElseThrow(() -> new AssertionError("No transport rate in force on " + on));
    }

    private BigDecimal rate(Employee employee, LocalDate on) {
        return valueService.numericValueOn(employee.getId(),
                        EmployeePayrollValueCodes.HOURLY_RATE, on)
                .orElseThrow(() -> new AssertionError("No rate in force on " + on));
    }

    private EmployeePayrollValueHistory row(Employee employee,
                                            EmployeePayrollValueDefinition definition,
                                            String value, LocalDate from, LocalDate until) {
        return EmployeePayrollValueHistory.builder()
                .employee(employee)
                .definition(definition)
                .valueType(definition.getValueType())
                .numericValue(new BigDecimal(value))
                .validFrom(from)
                .validUntil(until)
                .build();
    }

    // ── Correcting what a period says, and withdrawing one entered by mistake ──
    //
    // Both were missing, and their absence is what produced the case this came
    // from: a rate typed as 400 for August could not be made 450 for August, so
    // it was entered from the 2nd instead — leaving 400 covering a single day and
    // August priced at it, because a month takes the rate in force on its first
    // day.

    /*
     * The complaint this closes: a rate written on the employee page did not
     * reach the payroll. The queued job rebuilds the monthly REPORT, and the
     * payroll item only re-prices when it notices it is stale — which, for a
     * change that leaves the report identical, it never did.
     */
    @Test
    @DisplayName("changing a value flags the payroll of every month it reaches")
    void changingAValueFlagsThePayroll() {
        var scenario = fixture.scenario().build();
        var item = scenario.item();
        assertThat(item.getNeedsRecalculation()).isFalse();

        valueService.changeValue(scenario.employee().getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("450.00"),
                item.getPeriod().withDayOfMonth(1), null, null);

        entityManager.flush();
        entityManager.clear();

        assertThat(payrollRunItemRepository.findById(item.getId()).orElseThrow()
                .getNeedsRecalculation()).isTrue();
    }

    @Test
    @DisplayName("a period can be corrected without moving when it applies")
    void correctingKeepsTheDates() {
        Employee employee = anEmployee();
        var period = valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("400.00"),
                LocalDate.of(2026, 8, 1), null, null);

        var corrected = valueService.correctPeriod(employee.getId(), period.getId(),
                new BigDecimal("450.00"), null, "greška pri unosu", null);

        assertThat(corrected.getValidFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(corrected.getNumericValue()).isEqualByComparingTo("450.00");

        // What was believed before is still readable — archived, not overwritten.
        entityManager.flush();
        entityManager.clear();
        var old = historyRepository.findById(period.getId()).orElseThrow();
        assertThat(old.getArchivedAt()).isNotNull();
        assertThat(old.getNumericValue()).isEqualByComparingTo("400.00");

        // And the corrected figure is the one in force on that date.
        assertThat(valueService.numericValueOn(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, LocalDate.of(2026, 8, 1)))
                .contains(new BigDecimal("450.000000"));
    }

    @Test
    @DisplayName("removing a period hands its days to a neighbour, never leaving a hole")
    void removingLeavesNoGap() {
        Employee employee = anEmployee();
        var first = valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("400.00"),
                LocalDate.of(2026, 8, 1), null, null);
        valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("450.00"),
                LocalDate.of(2026, 8, 2), null, null);

        // Exactly the owner's case: withdraw the one-day 400.
        valueService.removePeriod(employee.getId(), first.getId(), null);
        entityManager.flush();
        entityManager.clear();

        // The 450 moved back to cover the 1st — the employee is not left without
        // a rate on a day they worked.
        assertThat(valueService.numericValueOn(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, LocalDate.of(2026, 8, 1)))
                .contains(new BigDecimal("450.000000"));
        assertThat(historyRepository.findById(first.getId()).orElseThrow().getArchivedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("removing the last period extends the one before it")
    void removingExtendsThePredecessor() {
        Employee employee = anEmployee();
        valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("400.00"),
                LocalDate.of(2026, 8, 1), null, null);
        var second = valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("450.00"),
                LocalDate.of(2026, 9, 1), null, null);

        valueService.removePeriod(employee.getId(), second.getId(), null);
        entityManager.flush();
        entityManager.clear();

        // September falls back to 400 rather than to nothing.
        assertThat(valueService.numericValueOn(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, LocalDate.of(2026, 9, 15)))
                .contains(new BigDecimal("400.000000"));
    }

    @Test
    @DisplayName("one employee's period cannot be touched through another's URL")
    void periodsBelongToTheirEmployee() {
        Employee employee = anEmployee();
        Employee other = anEmployee();
        var period = valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("400.00"),
                LocalDate.of(2026, 8, 1), null, null);

        assertThatThrownBy(() -> valueService.removePeriod(other.getId(), period.getId(), null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("a period cannot be withdrawn twice")
    void removingIsNotRepeatable() {
        Employee employee = anEmployee();
        var period = valueService.changeValue(employee.getId(),
                EmployeePayrollValueCodes.HOURLY_RATE, new BigDecimal("400.00"),
                LocalDate.of(2026, 8, 1), null, null);

        valueService.removePeriod(employee.getId(), period.getId(), null);
        assertThatThrownBy(() -> valueService.removePeriod(employee.getId(), period.getId(), null))
                .isInstanceOf(ConflictException.class);
    }
}
