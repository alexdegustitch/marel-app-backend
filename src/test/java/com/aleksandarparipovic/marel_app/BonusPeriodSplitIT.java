package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonusRepository;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonusService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inserting a bonus category into the middle of an existing spell.
 *
 * <p>THE OWNER'S EXAMPLE, which this encodes: old category until 7 July, the new
 * one from 7 July to 2 August, and then the OLD one again from 3 August. The
 * third row is the part that is easy to forget and impossible to see — without
 * it the employee silently has no bonus category from the end date onwards, and
 * nothing on any screen would say so.
 *
 * <p>Three rows rather than an edit of one: the exclusion constraint
 * {@code ex_employees_bonus_history_no_overlap} refuses an insert into a live
 * spell, and rewriting the existing row would erase what was already paid.
 */
@Transactional
class BonusPeriodSplitIT extends AbstractIntegrationTest {

    @Autowired private EmployeeBonusService bonusService;
    @Autowired private EmployeeBonusRepository bonusRepository;
    @Autowired private BonusCategoryRepository categoryRepository;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("a closed range puts the previous category back afterwards")
    void aClosedRangeResumesThePreviousCategory() {
        Employee employee = fixture.scenario().build().employee();
        BonusCategory original = anyCategory(0);
        BonusCategory replacement = anyCategory(1);

        bonusService.changeBonus(employee, original, LocalDate.of(2026, 1, 1), null, null);
        bonusService.changeBonus(employee, replacement,
                LocalDate.of(2026, 7, 7), LocalDate.of(2026, 8, 2), null);

        List<EmployeeBonus> spells = spells(employee);

        assertThat(spells).as("old, new, then old again — three rows").hasSize(3);

        assertThat(spells.get(0).getBonusCategory().getId()).isEqualTo(original.getId());
        assertThat(spells.get(0).getEndDate())
                .as("closed the day BEFORE the new spell, inclusive end")
                .isEqualTo(LocalDate.of(2026, 7, 6));

        assertThat(spells.get(1).getBonusCategory().getId()).isEqualTo(replacement.getId());
        assertThat(spells.get(1).getStartDate()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(spells.get(1).getEndDate()).isEqualTo(LocalDate.of(2026, 8, 2));

        assertThat(spells.get(2).getBonusCategory().getId())
                .as("the ORIGINAL category resumes — not left with nothing")
                .isEqualTo(original.getId());
        assertThat(spells.get(2).getStartDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(spells.get(2).getEndDate()).as("and stays open").isNull();
    }

    @Test
    @DisplayName("an open-ended change simply closes the previous spell")
    void anOpenEndedChangeLeavesTwoSpells() {
        Employee employee = fixture.scenario().build().employee();
        BonusCategory original = anyCategory(0);
        BonusCategory replacement = anyCategory(1);

        bonusService.changeBonus(employee, original, LocalDate.of(2026, 1, 1), null, null);
        bonusService.changeBonus(employee, replacement, LocalDate.of(2026, 7, 7), null, null);

        List<EmployeeBonus> spells = spells(employee);

        assertThat(spells).as("nothing to resume — the new spell runs on").hasSize(2);
        assertThat(spells.get(1).getEndDate()).isNull();
    }

    @Test
    @DisplayName("a change cannot start before the spell it replaces")
    void refusesAStartBeforeTheCurrentSpell() {
        Employee employee = fixture.scenario().build().employee();
        BonusCategory original = anyCategory(0);
        BonusCategory replacement = anyCategory(1);

        bonusService.changeBonus(employee, original, LocalDate.of(2026, 6, 1), null, null);

        assertThatThrownBy(() -> bonusService.changeBonus(employee, replacement,
                LocalDate.of(2026, 3, 1), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mora da počne posle");
    }

    private List<EmployeeBonus> spells(Employee employee) {
        return bonusRepository.findAll().stream()
                .filter(b -> b.getEmployee().getId().equals(employee.getId()))
                .sorted(Comparator.comparing(EmployeeBonus::getStartDate))
                .toList();
    }

    /** Its own categories: the test schema ships none, and borrowing another
     *  test's would couple this file to that one's setup. */
    private BonusCategory anyCategory(int index) {
        return categoryRepository.saveAndFlush(BonusCategory.builder()
                .categoryNo("IT-SPLIT-" + index + "-" + COUNTER.incrementAndGet())
                .categoryName("Split bonus " + index)
                .bonusAmount(new java.math.BigDecimal("1000.00"))
                .minHours(new java.math.BigDecimal("0.00"))
                .active(true)
                .validFrom(LocalDate.of(2020, 1, 1))
                .build());
    }

    private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();
}
