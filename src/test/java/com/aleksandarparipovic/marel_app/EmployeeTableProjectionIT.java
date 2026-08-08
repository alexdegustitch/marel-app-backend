package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.department_head.DepartmentHeadPeriod;
import com.aleksandarparipovic.marel_app.department_head.DepartmentHeadPeriodRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.EmployeeService;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The employees table actually loads.
 *
 * <p>WHY THIS EXISTS. The table's two projection paths — the JPQL
 * {@code findEmployeesWithCurrentBonus} and the Criteria
 * {@code searchWithBonus} — are built from column lists and joins that nothing
 * executed in a test. When a correlated subquery for "is this person a
 * department head today" was added to the SELECT, the whole suite of 306 tests
 * stayed green while the endpoint threw
 * {@code JpaSystemException: Could not locate TableGroup} on the first real
 * request. Every column added to those projections from now on is covered by
 * simply running them.
 *
 * <p>Executing the query IS most of the assertion here: a projection that
 * cannot be built throws, and a constructor arity or type that drifts from the
 * select list throws too.
 */
@Transactional
class EmployeeTableProjectionIT extends AbstractIntegrationTest {
    // Rolled back, unlike PayrollValueBackfillIT which cannot be because it
    // shells out to psql. This suite shares one database, and the schemes and
    // employees seeded here would otherwise invalidate other tests' schemes —
    // which is exactly what happened on the first run of this file.

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentHeadPeriodRepository headRepository;
    @Autowired private EmployeeService employeeService;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("both projection paths execute and agree on the same employee")
    void bothProjectionPathsExecute() {
        Employee employee = fixture.scenario().build().employee();

        Page<EmployeeWithBonusView> viaJpql =
                employeeRepository.findEmployeesWithCurrentBonus(PageRequest.of(0, 50));
        Page<EmployeeWithBonusView> viaCriteria =
                employeeRepository.searchWithBonus(null, PageRequest.of(0, 50));

        assertThat(row(viaJpql, employee.getId()))
                .as("the JPQL projection returns the employee it was given")
                .isNotNull();
        assertThat(row(viaCriteria, employee.getId()))
                .as("the Criteria projection returns the same employee")
                .isNotNull();

        // The name is derived by the database, so a projection that dropped
        // first/last would still produce a row — check it carries the parts.
        assertThat(row(viaCriteria, employee.getId()).getFullName())
                .isEqualTo(employee.getFirstName() + " " + employee.getLastName());
    }

    @Test
    @DisplayName("department head is true only while a period covers today")
    void departmentHeadFollowsThePeriod() {
        Employee employee = fixture.scenario().build().employee();

        assertThat(row(employeeService.getEmployeeBonusTable(PageRequest.of(0, 50)), employee.getId())
                .isDepartmentHead())
                .as("nobody is a head without a period")
                .isFalse();

        // A spell that ENDED yesterday must not count — this is the case a
        // plain "has any period" check would get wrong.
        headRepository.saveAndFlush(DepartmentHeadPeriod.builder()
                .department(employee.getDepartment())
                .employee(employee)
                .validFrom(LocalDate.now().minusDays(30))
                .validTo(LocalDate.now().minusDays(1))
                .build());

        assertThat(row(employeeService.getEmployeeBonusTable(PageRequest.of(0, 50)), employee.getId())
                .isDepartmentHead())
                .as("an expired spell does not make somebody head today")
                .isFalse();

        headRepository.saveAndFlush(DepartmentHeadPeriod.builder()
                .department(employee.getDepartment())
                .employee(employee)
                .validFrom(LocalDate.now())
                .build());

        assertThat(row(employeeService.getEmployeeBonusTable(PageRequest.of(0, 50)), employee.getId())
                .isDepartmentHead())
                .as("an open spell starting today does")
                .isTrue();
    }

    private static EmployeeWithBonusView row(Page<EmployeeWithBonusView> page, Long employeeId) {
        return page.getContent().stream()
                .filter(v -> employeeId.equals(v.getEmployeeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Employee " + employeeId + " missing from the projection"));
    }
}
