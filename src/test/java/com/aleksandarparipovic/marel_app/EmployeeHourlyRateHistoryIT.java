package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee.EmployeeService;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeePatchRequest;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueHistory;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changing an employee's hourly rate has to land where payroll reads it.
 *
 * <p>Before this, {@code EmployeeService} wrote {@code employees.hourly_rate} and
 * rewrote every unlocked payroll item directly, while
 * {@code PayrollRunItemService.hourlyRateFor} resolved HOURLY_RATE from the value
 * history and only fell back to the column when an employee had no history at
 * all. Two writers, and the reader preferred the one the employee screen never
 * touched — so for anyone who had a history row the change applied, looked
 * correct, and was reverted by the next recalculation.
 *
 * <p>The retroactive rewrite was the second half of the same defect: it repriced
 * months the new rate was never in force for. What replaces it is marking the
 * items stale, so each one re-resolves the rate for ITS OWN month.
 */
@Transactional
class EmployeeHourlyRateHistoryIT extends AbstractIntegrationTest {

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeePayrollValueService valueService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private PayrollRunItemRepository itemRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;
    @Autowired private com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService payrollRunItemService;
    /** The test context has no web layer, so no auto-configured mapper either. */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Built the way the controller builds it. The DTO has no setters, and adding
     * them for a test would widen production surface to suit the test rather than
     * the other way round.
     */
    private EmployeePatchRequest patch(String rate, LocalDate effectiveFrom) {
        Map<String, Object> body = new HashMap<>();
        body.put("hourlyRate", rate);
        if (effectiveFrom != null) {
            body.put("hourlyRateEffectiveFrom", effectiveFrom.toString());
        }
        return objectMapper.convertValue(body, EmployeePatchRequest.class);
    }

    private List<EmployeePayrollValueHistory> rateHistory(Long employeeId) {
        return valueService.getHistory(employeeId, EmployeePayrollValueCodes.HOURLY_RATE);
    }

    // ── where the rate is recorded ──────────────────────────────────────────

    @Test
    @DisplayName("a rate change is recorded in the value history, not only on the employee")
    void aRateChangeIsRecordedInTheHistory() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        Long employeeId = scenario.employee().getId();

        assertThat(rateHistory(employeeId)).isEmpty();

        employeeService.patchEmployee(employeeId, patch("500.00", null));

        assertThat(rateHistory(employeeId))
                .singleElement()
                .satisfies(period -> {
                    assertThat(period.getNumericValue()).isEqualByComparingTo("500.00");
                    // Payroll prices a month at its START date. A rate recorded from
                    // today would not be in force for the month being calculated, so
                    // the correction would silently do nothing until the next one.
                    assertThat(period.getValidFrom()).isEqualTo(LocalDate.now().withDayOfMonth(1));
                    assertThat(period.getValidUntil()).isNull();
                });

        // The column is still written — phase 7 drops it, and until then a client
        // reading it must not see something different from the history.
        assertThat(employeeRepository.findById(employeeId).orElseThrow().getHourlyRate())
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("an explicit start date is honoured, so a rate can be corrected backwards")
    void anExplicitStartDateIsHonoured() {
        var scenario = fixture.scenario().build();
        Long employeeId = scenario.employee().getId();

        employeeService.patchEmployee(employeeId, patch("610.00", LocalDate.of(2025, 1, 1)));

        assertThat(rateHistory(employeeId))
                .singleElement()
                .satisfies(period -> {
                    assertThat(period.getValidFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
                    assertThat(period.getNumericValue()).isEqualByComparingTo("610.00");
                });
    }

    // ── the shape of the history after ordinary edits ───────────────────────

    @Test
    @DisplayName("fixing a typo the same month corrects the period instead of splitting it")
    void correctingTheSameMonthDoesNotSplitTheHistory() {
        var scenario = fixture.scenario().build();
        Long employeeId = scenario.employee().getId();
        LocalDate from = LocalDate.of(2026, 9, 1);

        employeeService.patchEmployee(employeeId, patch("500.00", from));
        // The administrator meant 550 and saves again. Two periods starting the
        // same month would assert a mid-month raise that never happened — and
        // refusing the second save would send them to delete a history row to fix
        // a number they had just typed.
        employeeService.patchEmployee(employeeId, patch("550.00", from));

        assertThat(rateHistory(employeeId))
                .singleElement()
                .satisfies(period -> {
                    assertThat(period.getValidFrom()).isEqualTo(from);
                    assertThat(period.getNumericValue()).isEqualByComparingTo("550.00");
                });
    }

    @Test
    @DisplayName("a real raise appends a period and closes the previous one")
    void aRaiseAppendsAPeriod() {
        var scenario = fixture.scenario().build();
        Long employeeId = scenario.employee().getId();

        employeeService.patchEmployee(employeeId, patch("400.00", LocalDate.of(2026, 8, 1)));
        employeeService.patchEmployee(employeeId, patch("600.00", LocalDate.of(2026, 9, 1)));

        assertThat(rateHistory(employeeId)).hasSize(2);

        // THE POINT OF THE WHOLE TABLE: August is still priced at the August rate.
        assertThat(valueService.numericValueOn(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                LocalDate.of(2026, 8, 1))).hasValueSatisfying(
                        v -> assertThat(v).isEqualByComparingTo("400.00"));
        assertThat(valueService.numericValueOn(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                LocalDate.of(2026, 9, 1))).hasValueSatisfying(
                        v -> assertThat(v).isEqualByComparingTo("600.00"));
    }

    @Test
    @DisplayName("saving the same rate again records nothing")
    void savingTheSameRateRecordsNothing() {
        var scenario = fixture.scenario().build();
        Long employeeId = scenario.employee().getId();
        LocalDate from = LocalDate.of(2026, 9, 1);

        employeeService.patchEmployee(employeeId, patch("500.00", from));
        employeeService.patchEmployee(employeeId, patch("500.00", from));

        assertThat(rateHistory(employeeId)).hasSize(1);
    }

    // ── what happens to the payroll items ───────────────────────────────────

    @Test
    @DisplayName("unlocked items are marked stale rather than repriced in place")
    void unlockedItemsAreMarkedStaleNotRepriced() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 9)).hourlyRate("420.00").build();
        Long employeeId = scenario.employee().getId();
        Long itemId = scenario.item().getId();

        assertThat(itemRepository.findById(itemId).orElseThrow().getHourlyRate())
                .isEqualByComparingTo("420.00");

        employeeService.patchEmployee(employeeId, patch("600.00", LocalDate.of(2026, 9, 1)));

        // The mark is a bulk UPDATE, which does not reach entities already loaded
        // in this transaction's persistence context. In production each request
        // brings its own; here the read has to be forced back to the database.
        entityManager.clear();

        PayrollRunItem item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.getNeedsRecalculation()).isTrue();

        // Still the OLD figure on the row itself. The new rate reaches the item
        // through a recalculation that resolves it for the item's own month — not
        // through an UPDATE that would have hit every open month alike.
        assertThat(item.getHourlyRate()).isEqualByComparingTo("420.00");
    }

    @Test
    @DisplayName("nothing is recorded and nothing is marked when the request omits the rate")
    void omittingTheRateChangesNothing() {
        var scenario = fixture.scenario().build();
        Long employeeId = scenario.employee().getId();
        Long itemId = scenario.item().getId();
        PayrollRunItem before = itemRepository.findById(itemId).orElseThrow();
        before.setNeedsRecalculation(false);
        itemRepository.saveAndFlush(before);
        entityManager.clear();

        employeeService.patchEmployee(employeeId, objectMapper.convertValue(
                Map.of("notes", "samo beleska"), EmployeePatchRequest.class));

        assertThat(rateHistory(employeeId)).isEmpty();
        assertThat(itemRepository.findById(itemId).orElseThrow().getNeedsRecalculation()).isFalse();
    }

    @Test
    @DisplayName("the value the payroll month resolves is the history's, not the column's")
    void payrollResolvesTheHistoryNotTheColumn() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 9)).hourlyRate("420.00").build();
        Long employeeId = scenario.employee().getId();

        employeeService.patchEmployee(employeeId, patch("777.00", LocalDate.of(2026, 9, 1)));

        // Force the column out of step with the history, the exact state the old
        // code produced. The history has to win.
        var employee = employeeRepository.findById(employeeId).orElseThrow();
        employee.setHourlyRate(new BigDecimal("111.00"));
        employeeRepository.saveAndFlush(employee);

        assertThat(valueService.numericValueOn(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                LocalDate.of(2026, 9, 1))).hasValueSatisfying(
                        v -> assertThat(v).isEqualByComparingTo("777.00"));
    }

    /*
     * "Reset" must mean the employee's own rate, not zero.
     *
     * The button sends an explicit null, and the service reads null as "take the
     * system rate again". Between the two sat @JsonSetter(nulls = AS_EMPTY),
     * whose empty value for a BigDecimal is ZERO — so the reset arrived as a
     * typed-in 0, the payroll recorded zero, and marked it OVERRIDDEN, which is
     * the opposite of what was asked. Read through the same mapper the web layer
     * uses, because the defect lived in the deserialisation and not in either
     * side's own logic.
     */
    @Test
    @DisplayName("resetting the hourly rate takes the employee's rate, not zero")
    void resettingTheRateTakesTheSystemValue() throws Exception {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        Long employeeId = scenario.employee().getId();

        valueService.changeValue(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                new java.math.BigDecimal("500.00"),
                scenario.item().getPeriod().withDayOfMonth(1), null, null);

        var mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();

        // Typed in: an override, as before.
        payrollRunItemService.patch(itemId, mapper.readValue("{\"hourlyRate\":300.00}",
                com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest.class));
        entityManager.flush();
        entityManager.clear();
        var overridden = itemRepository.findById(itemId).orElseThrow();
        assertThat(overridden.getHourlyRate()).isEqualByComparingTo("300.00");
        assertThat(overridden.getHourlyRateOverridden()).isTrue();

        // Reset: back to the employee's own rate, and no longer an override.
        payrollRunItemService.patch(itemId, mapper.readValue("{\"hourlyRate\":null}",
                com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest.class));
        entityManager.flush();
        entityManager.clear();
        var reset = itemRepository.findById(itemId).orElseThrow();
        assertThat(reset.getHourlyRate()).isEqualByComparingTo("500.00");
        assertThat(reset.getHourlyRateOverridden()).isFalse();
    }
}
