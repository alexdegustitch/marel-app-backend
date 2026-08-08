package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_work_category.ChangeWorkCategoryRequest;
import com.aleksandarparipovic.marel_app.employee_work_category.EmployeeWorkCategoryPeriodDto;
import com.aleksandarparipovic.marel_app.employee_work_category.EmployeeWorkCategoryService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Moving somebody between default work categories keeps the previous answer.
 *
 * <p>The point of the table added by {@code 2026-09-22-01} is that "what did
 * this person work in in March" survives a change. A transition that overwrote
 * the current row instead of closing it would still look right on screen and
 * would have destroyed exactly that.
 */
@Transactional
class EmployeeWorkCategoryPeriodIT extends AbstractIntegrationTest {

    @Autowired private EmployeeWorkCategoryService service;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("a change closes the old spell the day before the new one starts")
    void aChangeClosesTheOldSpell() {
        Employee employee = fixture.scenario().build().employee();
        WorkCodeCategory first = assignable(0);
        WorkCodeCategory second = assignable(1);

        service.change(employee.getId(), request(first.getId(), LocalDate.of(2026, 1, 1), null));
        service.change(employee.getId(), request(second.getId(), LocalDate.of(2026, 6, 1), null));

        List<EmployeeWorkCategoryPeriodDto> history = service.history(employee.getId());

        assertThat(history).as("both spells survive; the old one is not overwritten").hasSize(2);

        assertThat(history.get(0).validFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(history.get(0).validTo()).as("the new spell stays open").isNull();

        assertThat(history.get(1).validTo())
                .as("inclusive end — the day BEFORE the new spell, not the same day")
                .isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(history.get(1).workCodeCategoryId())
                .as("the old spell still points at the old category")
                .isEqualTo(first.getId());
    }

    @Test
    @DisplayName("the mirror on employees follows the spell in force")
    void theMirrorFollows() {
        Employee employee = fixture.scenario().build().employee();
        WorkCodeCategory category = assignable(0);

        service.change(employee.getId(), request(category.getId(), LocalDate.now(), null));

        // The trigger writes the mirror in the DATABASE. Without clearing, the
        // persistence context hands back the Employee it already has and the
        // assertion would test Hibernate's cache instead of the trigger.
        entityManager.flush();
        entityManager.clear();

        Employee reloaded = employeeRepository.findById(employee.getId()).orElseThrow();
        assertThat(reloaded.getDefaultWorkCategory())
                .as("employees.default_work_category_id is trigger-maintained from the period")
                .isNotNull();
        assertThat(reloaded.getDefaultWorkCategory().getId()).isEqualTo(category.getId());
    }

    @Test
    @DisplayName("a category nobody can be assigned to is refused")
    void refusesANonBaseOperation() {
        Employee employee = fixture.scenario().build().employee();

        WorkCodeCategory notAssignable = categoryRepository.findAll().stream()
                .filter(c -> c.getArchivedAt() == null)
                .findFirst()
                .orElseThrow();
        ReflectionTestUtils.setField(notAssignable, "baseOperation", Boolean.FALSE);
        categoryRepository.saveAndFlush(notAssignable);

        assertThatThrownBy(() ->
                service.change(employee.getId(), request(notAssignable.getId(), LocalDate.now(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nije osnovna operacija");
    }

    @Test
    @DisplayName("a new spell cannot start before the one it replaces")
    void refusesAStartBeforeTheCurrentSpell() {
        Employee employee = fixture.scenario().build().employee();
        WorkCodeCategory first = assignable(0);
        WorkCodeCategory second = assignable(1);

        service.change(employee.getId(), request(first.getId(), LocalDate.of(2026, 6, 1), null));

        assertThatThrownBy(() ->
                service.change(employee.getId(), request(second.getId(), LocalDate.of(2026, 3, 1), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mora da počne posle");
    }

    private WorkCodeCategory assignable(int index) {
        List<WorkCodeCategory> usable = categoryRepository.findAll().stream()
                .filter(c -> c.getArchivedAt() == null)
                .filter(c -> Boolean.TRUE.equals(c.getBaseOperation()))
                .toList();
        assertThat(usable.size())
                .as("the fixture schema needs at least two assignable categories")
                .isGreaterThan(index);
        return usable.get(index);
    }

    private static ChangeWorkCategoryRequest request(Long categoryId, LocalDate from, LocalDate to) {
        ChangeWorkCategoryRequest r = new ChangeWorkCategoryRequest();
        ReflectionTestUtils.setField(r, "workCodeCategoryId", categoryId);
        ReflectionTestUtils.setField(r, "validFrom", from);
        ReflectionTestUtils.setField(r, "validTo", to);
        return r;
    }
}
