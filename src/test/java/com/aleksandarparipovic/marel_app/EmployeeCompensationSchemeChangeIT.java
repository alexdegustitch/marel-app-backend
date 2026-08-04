package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Moving an employee to a different compensation scheme.
 *
 * <p><b>This method had no test at all.</b> It was written to close the open
 * period and insert a new one, which is the right shape — but the close was
 * queued as a JPA update while the insert went out immediately, so it failed on
 * {@code ex_ecsh_no_overlap} every time an employee already had an open period.
 * In other words the normal case, which is every case in production.
 *
 * <p>This is also where phase 5's D1 rules land: a scheme change must take effect
 * on the first day of the following month, and a payroll month must resolve to
 * exactly one scheme.
 */
@Transactional
class EmployeeCompensationSchemeChangeIT extends AbstractIntegrationTest {

    @Autowired private EmployeeCompensationSchemeService schemeService;
    @Autowired private CompensationSchemeRepository schemeRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository schemeHistoryRepository;

    /** The fixture gives every employee an open STANDARD period from 2020-01-01. */
    private Employee anEmployeeOnStandard() {
        return fixture.scenario().build().employee();
    }

    private CompensationScheme scheme(String code) {
        return schemeRepository.findByCode(code).orElseThrow();
    }

    @Test
    @DisplayName("a change closes the open period the day before and opens the new one")
    void changeClosesThenOpens() {
        Employee employee = anEmployeeOnStandard();
        LocalDate from = LocalDate.now().plusMonths(1).withDayOfMonth(1);

        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(),
                from, "Moved to the restricted scheme");

        List<EmployeeCompensationSchemeHistory> history = schemeService.getHistory(employee.getId());
        assertThat(history).hasSize(2);

        EmployeeCompensationSchemeHistory closed = history.stream()
                .filter(p -> CompensationSchemeCodes.STANDARD.equals(p.getCompensationScheme().getCode()))
                .findFirst().orElseThrow();
        EmployeeCompensationSchemeHistory opened = history.stream()
                .filter(p -> CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT
                        .equals(p.getCompensationScheme().getCode()))
                .findFirst().orElseThrow();

        // Inclusive valid_until: the two touch on 31 August / 1 September without
        // overlapping, so no work date falls between them and none is covered twice.
        assertThat(closed.getValidUntil()).isEqualTo(from.minusDays(1));
        assertThat(opened.getValidFrom()).isEqualTo(from);
        assertThat(opened.getValidUntil()).isNull();
    }

    @Test
    @DisplayName("history is appended, never rewritten — the old period keeps its scheme")
    void theOldPeriodKeepsItsScheme() {
        Employee employee = anEmployeeOnStandard();

        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(),
                LocalDate.now().plusMonths(1).withDayOfMonth(1), null);

        // Work already recorded keeps the policy that was actually applied to it.
        // Editing the existing row's scheme instead would retroactively change what
        // years of work were worth.
        assertThat(schemeService.getHistory(employee.getId()))
                .filteredOn(p -> p.getValidFrom().equals(LocalDate.of(2020, 1, 1)))
                .singleElement()
                .satisfies(p -> assertThat(p.getCompensationScheme().getCode())
                        .isEqualTo(CompensationSchemeCodes.STANDARD));
    }

    @Test
    @DisplayName("a second change appends a third period")
    void twoChangesInSequence() {
        Employee employee = anEmployeeOnStandard();

        LocalDate first = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate second = LocalDate.now().plusMonths(4).withDayOfMonth(1);
        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(), first, null);
        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.STANDARD).getId(), second, null);

        assertThat(schemeService.getHistory(employee.getId())).hasSize(3);
        assertThat(schemeService.getHistory(employee.getId()))
                .filteredOn(p -> p.getValidFrom().equals(first))
                .singleElement()
                .satisfies(p -> assertThat(p.getValidUntil()).isEqualTo(second.minusDays(1)));
    }

    @Test
    @DisplayName("moving to the scheme already in force is refused")
    void sameSchemeIsRefused() {
        Employee employee = anEmployeeOnStandard();

        assertThatThrownBy(() -> schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.STANDARD).getId(),
                LocalDate.of(2026, 9, 1), null))
                .isInstanceOf(ConflictException.class);
    }

    // ── D1: a change lands on the first of a month, and not this one ────────

    @Test
    @DisplayName("13. a mid-month date is refused rather than snapped forward")
    void aMidMonthDateIsRefused() {
        Employee employee = anEmployeeOnStandard();

        // "from 15 September" and "from 1 October" are different requests. Snapping
        // one into the other silently would mean the confirmation screen shows a
        // date the system did not use.
        assertThatThrownBy(() -> schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(),
                LocalDate.now().plusMonths(1).withDayOfMonth(15), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("prvog dana u mesecu");
    }

    @Test
    @DisplayName("13. a change dated inside the current month is refused")
    void thisMonthIsTooLate() {
        Employee employee = anEmployeeOnStandard();

        // The current month is already being calculated under the existing scheme.
        // Letting a change land inside it would give that month two schemes, which
        // PayrollSchemeScopeService refuses to calculate at all.
        assertThatThrownBy(() -> schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(),
                LocalDate.now().withDayOfMonth(1), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("najranije");
    }

    @Test
    @DisplayName("13. the first day of next month is accepted, and no month spans two schemes")
    void firstOfNextMonthIsAccepted() {
        Employee employee = anEmployeeOnStandard();
        LocalDate from = LocalDate.now().plusMonths(1).withDayOfMonth(1);

        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(), from, null);

        List<EmployeeCompensationSchemeHistory> history = schemeService.getHistory(employee.getId());
        assertThat(history).hasSize(2);

        // The old period ends on the last day of this month and the new one starts
        // on the first of next. Every payroll month therefore falls entirely inside
        // exactly one of them — which is the whole point of D1.
        EmployeeCompensationSchemeHistory closed = history.stream()
                .filter(p -> p.getValidUntil() != null).findFirst().orElseThrow();
        assertThat(closed.getValidUntil()).isEqualTo(from.minusDays(1));
        assertThat(closed.getValidUntil().getDayOfMonth())
                .isEqualTo(closed.getValidUntil().lengthOfMonth());
    }

    @Test
    @DisplayName("13. the FIRST assignment may start on any day — it is not a change")
    void theInitialAssignmentIsExempt() {
        // A new employee starts on their hire date, whatever day that is. There is
        // no earlier month for it to split, so the rule does not apply.
        Employee employee = fixture.scenario().build().employee();
        schemeHistoryRepository.findHistoryFor(employee.getId())
                .forEach(p -> schemeHistoryRepository.delete(p));
        schemeHistoryRepository.flush();

        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.STANDARD).getId(),
                LocalDate.of(2026, 3, 17), "hired mid-month");

        assertThat(schemeService.getHistory(employee.getId()))
                .singleElement()
                .satisfies(p -> assertThat(p.getValidFrom()).isEqualTo(LocalDate.of(2026, 3, 17)));
    }

    @Test
    @DisplayName("13. a scheduled change can be replaced through a named operation")
    void aScheduledChangeCanBeReplaced() {
        Employee employee = anEmployeeOnStandard();
        LocalDate from = LocalDate.now().plusMonths(1).withDayOfMonth(1);

        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(), from, null);

        // changeScheme refuses, on purpose: a future decision must not vanish
        // because of an edit to the present. But somebody has to be able to correct
        // a mistake without opening the database.
        schemeService.replaceScheduledChange(employee.getId(),
                scheme(CompensationSchemeCodes.COMMERCIAL).getId(), from, "corrected");

        List<EmployeeCompensationSchemeHistory> live = schemeService.getHistory(employee.getId()).stream()
                .filter(p -> p.getArchivedAt() == null).toList();

        assertThat(live).hasSize(2);
        assertThat(live.stream().filter(p -> p.getValidUntil() == null).findFirst().orElseThrow()
                .getCompensationScheme().getCode()).isEqualTo(CompensationSchemeCodes.COMMERCIAL);

        // The superseded period is archived, not deleted: what was scheduled, and
        // that somebody changed their mind, are both part of the record. It is not
        // in getHistory — that lists the periods in force — but the row is there,
        // and the audit trail carries who archived it and when.
        assertThat(schemeHistoryRepository.findAll().stream()
                .filter(p -> p.getEmployee().getId().equals(employee.getId()))
                .filter(p -> p.getArchivedAt() != null)
                .toList())
                .as("the replaced schedule is archived, not erased")
                .hasSize(1);
    }

    @Test
    @DisplayName("a change dated on or before an existing future period is refused, not truncated")
    void aFuturePeriodBlocksAnEarlierChange() {
        Employee employee = anEmployeeOnStandard();
        LocalDate far = LocalDate.now().plusMonths(4).withDayOfMonth(1);
        LocalDate near = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).getId(), far, null);

        assertThatThrownBy(() -> schemeService.changeScheme(employee.getId(),
                scheme(CompensationSchemeCodes.STANDARD).getId(), near, null))
                .isInstanceOf(ConflictException.class);
    }
}
