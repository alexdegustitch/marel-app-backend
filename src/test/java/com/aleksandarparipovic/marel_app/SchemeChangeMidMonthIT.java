package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scheme change never splits a payroll month.
 *
 * <p>WHY. {@code PayrollSchemeScopeService} refuses a month that touches more
 * than one scheme — "Obračunski mesec mora imati tačno jedan" — and the month's
 * payslip then cannot be produced at all. A first version of this test wrote two
 * overlapping periods straight through the repository to find out what a split
 * month pays; the answer was that it pays nothing, because the calculation
 * throws. That is why the service now MOVES a mid-month date instead of
 * accepting it.
 *
 * <p>The owner chose moving over refusing: any date is accepted, and the period
 * that comes back says which date was actually used, so the screen can report
 * "važiće od 1. oktobra" rather than rejecting the form.
 *
 * <p>The business rules still describe a "union over every period overlapping
 * the month". That description predates the guard and no longer holds — nothing
 * can produce a split month through the application any more.
 */
@Transactional
class SchemeChangeMidMonthIT extends AbstractIntegrationTest {

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EmployeeCompensationSchemeService schemeService;
    @Autowired private CompensationSchemeRepository schemeRepository;

    @Test
    @DisplayName("a mid-month date is moved to the first of the following month")
    void midMonthDateIsMoved() {
        Employee employee = fixture.scenario().build().employee();
        CompensationScheme target = scheme(CompensationSchemeCodes.COMMERCIAL);

        LocalDate requested = LocalDate.now().plusMonths(2).withDayOfMonth(17);

        EmployeeCompensationSchemeHistory created =
                schemeService.changeScheme(employee.getId(), target.getId(), requested, "test");

        assertThat(created.getValidFrom().getDayOfMonth())
                .as("a payroll month takes exactly one scheme, so a change starts on the 1st")
                .isEqualTo(1);
        assertThat(created.getValidFrom())
                .as("the first of ITS OWN month — picking the 17th means that month, not the next")
                .isEqualTo(requested.withDayOfMonth(1));
    }

    @Test
    @DisplayName("a date already on the first is kept as it is")
    void aFirstOfMonthIsKept() {
        Employee employee = fixture.scenario().build().employee();
        CompensationScheme target = scheme(CompensationSchemeCodes.COMMERCIAL);

        LocalDate requested = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        EmployeeCompensationSchemeHistory created =
                schemeService.changeScheme(employee.getId(), target.getId(), requested, "test");

        assertThat(created.getValidFrom())
                .as("nothing to move — the month is already clean")
                .isEqualTo(requested);
    }

    @Test
    @DisplayName("a date inside the current month lands on the first of it")
    void aDateInTheCurrentMonthLandsOnTheFirst() {
        Employee employee = fixture.scenario().build().employee();
        CompensationScheme target = scheme(CompensationSchemeCodes.COMMERCIAL);

        // Mid-CURRENT-month: the owner's case. Today is the 8th, the change is
        // dated the 20th, and the whole current month goes onto the new scheme.
        LocalDate requested = LocalDate.now().withDayOfMonth(20);

        EmployeeCompensationSchemeHistory created =
                schemeService.changeScheme(employee.getId(), target.getId(), requested, "test");

        assertThat(created.getValidFrom())
                .as("the current month is recalculated under the new scheme, from its first day")
                .isEqualTo(LocalDate.now().withDayOfMonth(1));
    }

    @Test
    @DisplayName("a change lands BETWEEN two existing periods instead of being refused")
    void insertsBetweenExistingPeriods() {
        Employee employee = fixture.scenario().build().employee();

        // The owner's screen: STANDARD running long, then COMMERCIAL from a later
        // date. Picking a date inside the first one used to be refused outright
        // because a later period existed.
        LocalDate future = LocalDate.now().plusMonths(3).withDayOfMonth(1);
        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.COMMERCIAL).getId(), future, "later period");

        LocalDate requested = LocalDate.now().plusMonths(1).withDayOfMonth(12);
        EmployeeCompensationSchemeHistory created = schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(), requested, "in between");

        assertThat(created.getValidFrom())
                .as("moved to the first of its own month")
                .isEqualTo(requested.withDayOfMonth(1));
        assertThat(created.getValidUntil())
                .as("ends the day before the period that already followed — not left open on top of it")
                .isEqualTo(future.minusDays(1));
    }

    @Test
    @DisplayName("a period that already starts on that date is REPLACED, not refused")
    void replacesAPeriodStartingOnTheSameDate() {
        Employee employee = fixture.scenario().build().employee();

        // The owner's log: picking the 13th normalises to the 1st, where a period
        // already begins. Refusing there told an administrator to fix it by hand,
        // with nothing on the screen to fix it with.
        LocalDate firstOfNext = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.COMMERCIAL).getId(), firstOfNext, "first");

        EmployeeCompensationSchemeHistory replaced = schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(),
                firstOfNext.withDayOfMonth(13), "second");

        assertThat(replaced.getValidFrom())
                .as("same period, same dates")
                .isEqualTo(firstOfNext);
        assertThat(replaced.getCompensationScheme().getCode())
                .as("its scheme is what changed")
                .isEqualTo(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT);
    }

    private CompensationScheme scheme(String code) {
        return schemeRepository.findByCode(code)
                .orElseThrow(() -> new AssertionError("No scheme " + code));
    }
}
