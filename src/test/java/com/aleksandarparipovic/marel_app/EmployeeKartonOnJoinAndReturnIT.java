package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.EmployeeService;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeCreateRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeePatchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeePatchRequest;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An employee gets a karton when they JOIN, and again when they RETURN.
 *
 * <p>Before this, a karton appeared only when somebody pressed "Kreiraj kartone"
 * for the month or recorded the employee's first shift — so between being
 * entered and being worked, a person was missing from the month they belong to.
 *
 * <p>The two openings use DIFFERENT months on purpose, which is most of what is
 * asserted here: joining files you under the month you were hired, returning
 * files you under the month you came back.
 */
@Transactional
class EmployeeKartonOnJoinAndReturnIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeRecordService employeeRecordService;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;
    /*
     * Constructed, not injected: this integration slice raises no web context, so
     * there is no ObjectMapper bean to autowire. A plain one deserializes the
     * request exactly as the controller's does — which is the point of building
     * the patch from JSON at all (see patch()).
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** A hire date safely outside the current month, so the two never collide. */
    private static final LocalDate LONG_AGO = LocalDate.of(2024, 2, 15);

    @Test
    @DisplayName("creating an employee creates their karton for the month they started")
    void theKartonExistsFromTheStart() {
        LocalDate start = LocalDate.of(2026, 5, 11);

        var created = employeeService.createEmployee(request(start));
        entityManager.flush();

        assertThat(employeeRecordService.existsForEmployeeAndMonth(created.getEmployeeId(), 2026, 5)).isTrue();
    }

    @Test
    @DisplayName("the month is employment start, not the month the form was filled in")
    void theMonthIsTheOneTheyStarted() {
        // An employee entered in advance or backdated belongs to the month they
        // began — the month every other record of theirs is filed under.
        LocalDate start = LocalDate.of(2025, 11, 3);

        var created = employeeService.createEmployee(request(start));
        entityManager.flush();

        assertThat(employeeRecordService.existsForEmployeeAndMonth(created.getEmployeeId(), 2025, 11)).isTrue();

        LocalDate today = LocalDate.now();
        if (today.getYear() != 2025 || today.getMonthValue() != 11) {
            assertThat(employeeRecordService
                    .existsForEmployeeAndMonth(created.getEmployeeId(), today.getYear(), today.getMonthValue()))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the karton is filed on the first of that month, whatever day they started")
    void theRecordStartsOnTheFirst() {
        LocalDate start = LocalDate.of(2026, 5, 27);

        var created = employeeService.createEmployee(request(start));
        entityManager.flush();

        Long recordId = employeeRecordService
                .findRecordIdForEmployeeAndMonth(created.getEmployeeId(), 2026, 5).orElseThrow();

        Object startDate = entityManager.createNativeQuery(
                        "SELECT start_date FROM employee_records WHERE id = :id")
                .setParameter("id", recordId)
                .getSingleResult();

        assertThat(startDate.toString()).isEqualTo("2026-05-01");
    }

    @Test
    @DisplayName("no payroll month is initialised behind the administrator's back")
    void addingOnePersonDoesNotInitialiseAMonth() {
        /*
         * The bulk creation publishes PayrollMonthInitEvent; this path
         * deliberately does not. Entering one person in the register must not
         * start a whole month's payroll as a side effect — asserted by there
         * being no payroll run for that month afterwards.
         */
        LocalDate start = LocalDate.of(2026, 4, 6);

        employeeService.createEmployee(request(start));
        entityManager.flush();

        Number runs = (Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM payroll_runs WHERE report_year = 2026 AND report_month = 4")
                .getSingleResult();

        assertThat(runs.longValue()).isZero();
    }

    // ── coming back ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("putting somebody back on the payroll opens their karton for THIS month")
    void returningToWorkOpensThisMonth() {
        Long employeeId = anEmployeeWhoLeft();

        employeeService.patchEmployee(employeeId, patch("{\"active\": true}"));
        entityManager.flush();

        LocalDate today = LocalDate.now();
        assertThat(employeeRecordService
                .existsForEmployeeAndMonth(employeeId, today.getYear(), today.getMonthValue()))
                .isTrue();
    }

    @Test
    @DisplayName("a returning employee is filed under now, not under when they were hired")
    void returningIsNotFiledUnderTheOriginalHireMonth() {
        // Employment start is the right answer only the FIRST time. Somebody who
        // comes back in August starts working in August.
        Long employeeId = anEmployeeWhoLeft();

        employeeService.patchEmployee(employeeId, patch("{\"active\": true}"));
        entityManager.flush();

        assertThat(kartonCount(employeeId)).isEqualTo(2);
    }

    @Test
    @DisplayName("saving an employee who is already active opens nothing")
    void anAlreadyActiveEmployeeIsNotReopened() {
        /*
         * THE REASON THIS REACTS TO THE TRANSITION AND NOT TO THE VALUE.
         *
         * The employee screen sends the WHOLE form on every save, so an active
         * employee arrives with active=true each time somebody corrects a phone
         * number. Reading the value would open a karton in whatever month that
         * happened.
         */
        var created = employeeService.createEmployee(request(LONG_AGO));
        entityManager.flush();

        employeeService.patchEmployee(created.getEmployeeId(), patch("{\"active\": true}"));
        entityManager.flush();

        LocalDate today = LocalDate.now();
        assertThat(employeeRecordService
                .existsForEmployeeAndMonth(created.getEmployeeId(), today.getYear(), today.getMonthValue()))
                .isFalse();
    }

    @Test
    @DisplayName("a save that says nothing about active opens nothing")
    void aPatchWithoutTheFlagOpensNothing() {
        var created = employeeService.createEmployee(request(LONG_AGO));
        entityManager.flush();

        employeeService.patchEmployee(created.getEmployeeId(), patch("{\"notes\": \"Nova beleška\"}"));
        entityManager.flush();

        assertThat(kartonCount(created.getEmployeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("taking somebody off the payroll answers, rather than saving and reporting 404")
    void deactivationReturnsTheEmployee() {
        /*
         * trg_02_employees_archived_at stamps archived_at the moment is_active
         * goes false. While findEmployeeWithBonusById still filtered on
         * `archivedAt is null`, this call saved the change and THEN threw
         * EntityNotFound on its own way out — the deactivation happened and the
         * caller was told it had not.
         */
        var created = employeeService.createEmployee(request(LONG_AGO));
        entityManager.flush();

        var returned = employeeService.patchEmployee(
                created.getEmployeeId(), patch("{\"active\": false}"));

        assertThat(returned.getEmployeeId()).isEqualTo(created.getEmployeeId());
    }

    @Test
    @DisplayName("taking somebody off the payroll opens nothing")
    void deactivationOpensNothing() {
        var created = employeeService.createEmployee(request(LONG_AGO));
        entityManager.flush();

        employeeService.patchEmployee(created.getEmployeeId(), patch("{\"active\": false}"));
        entityManager.flush();

        assertThat(kartonCount(created.getEmployeeId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a return does not initialise a payroll month either")
    void returningDoesNotInitialiseAMonth() {
        Long employeeId = anEmployeeWhoLeft();

        employeeService.patchEmployee(employeeId, patch("{\"active\": true}"));
        entityManager.flush();

        LocalDate today = LocalDate.now();
        Number runs = (Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM payroll_runs WHERE report_year = :y AND report_month = :m")
                .setParameter("y", today.getYear())
                .setParameter("m", today.getMonthValue())
                .getSingleResult();

        assertThat(runs.longValue()).isZero();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Hired long ago, since taken off the payroll — through the real path. */
    private Long anEmployeeWhoLeft() {
        var created = employeeService.createEmployee(request(LONG_AGO));
        entityManager.flush();

        employeeService.patchEmployee(created.getEmployeeId(), patch("{\"active\": false}"));
        entityManager.flush();

        return created.getEmployeeId();
    }

    /** Built from JSON, because the request carries getters and no setters. */
    private EmployeePatchRequest patch(String json) {
        try {
            return objectMapper.readValue(json, EmployeePatchRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long kartonCount(Long employeeId) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM employee_records WHERE employee_id = :id")
                .setParameter("id", employeeId)
                .getSingleResult()).longValue();
    }

    private EmployeeCreateRequest request(LocalDate employmentStart) {
        Department department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.saveAndFlush(
                        Department.builder().name("IT-DEPT").active(true).build()));

        // A scheme that earns no performance bonus, so no bonus category is
        // required — this test is about the karton, not about bonuses.
        CompensationScheme scheme = fixture.ensureScheme(
                "IT_NO_BONUS", "Bez bonusa", true, false);

        EmployeeCreateRequest request = new EmployeeCreateRequest();
        request.setEmployeeNo("IT-" + COUNTER.incrementAndGet());
        request.setFirstName("Novi");
        request.setLastName("Radnik");
        request.setDepartmentId(department.getId());
        request.setCompensationSchemeId(scheme.getId());
        request.setEmploymentStartDate(employmentStart);
        return request;
    }
}
