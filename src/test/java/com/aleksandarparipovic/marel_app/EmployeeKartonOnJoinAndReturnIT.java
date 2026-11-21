package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.EmployeeService;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeCreateRequest;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeePatchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeePatchRequest;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueService;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An employee gets a karton when they JOIN, and again when they RETURN.
 *
 * <p>Before this, a karton appeared only when somebody pressed "Kreiraj kartone"
 * for the month or recorded the employee's first shift — so between being
 * entered and being worked, a person was missing from the month they belong to.
 *
 * <p>Joining opens the karton for the month of employment start, and ONLY when
 * that month is the current one: somebody entered in advance, or backdated to an
 * earlier month, opens nothing — a month that has not begun or is already behind
 * us is filled by "Kreiraj kartone" or by the first shift, not as a side effect
 * of adding one person to the register. Returning files you under the month you
 * came back, whenever that is.
 */
@Transactional
class EmployeeKartonOnJoinAndReturnIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeRecordService employeeRecordService;
    @Autowired private EmployeePayrollValueService valueService;
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

    /**
     * The month the karton is opened for — which is now the ONLY month creation
     * ever opens one for, so the dates here are relative to it rather than
     * written out: a fixed 2026-05 would stop exercising the rule next June.
     */
    private static final YearMonth THIS_MONTH = YearMonth.now();

    @Test
    @DisplayName("somebody who starts this month has their karton from the start")
    void theKartonExistsFromTheStart() {
        LocalDate start = THIS_MONTH.atDay(11);

        var created = employeeService.createEmployee(request(start));
        entityManager.flush();

        assertThat(employeeRecordService.existsForEmployeeAndMonth(
                created.getEmployeeId(), THIS_MONTH.getYear(), THIS_MONTH.getMonthValue())).isTrue();
    }

    @Test
    @DisplayName("somebody backdated to an earlier month opens nothing")
    void aBackdatedHireOpensNothing() {
        // A month already behind us is not something adding one person to the
        // register may write into — "Kreiraj kartone" for that month, or the
        // employee's first shift, is what fills it.
        LocalDate start = THIS_MONTH.minusMonths(3).atDay(3);

        var created = employeeService.createEmployee(request(start));
        entityManager.flush();

        assertThat(kartonCount(created.getEmployeeId())).isZero();
    }

    @Test
    @DisplayName("somebody entered in advance opens nothing either")
    void aFutureHireOpensNothing() {
        LocalDate start = THIS_MONTH.plusMonths(1).atDay(1);

        var created = employeeService.createEmployee(request(start));
        entityManager.flush();

        assertThat(kartonCount(created.getEmployeeId())).isZero();
    }

    @Test
    @DisplayName("the karton is filed on the first of the month, whatever day they started")
    void theRecordStartsOnTheFirst() {
        LocalDate start = THIS_MONTH.atDay(27);

        var created = employeeService.createEmployee(request(start));
        entityManager.flush();

        Long recordId = employeeRecordService
                .findRecordIdForEmployeeAndMonth(
                        created.getEmployeeId(), THIS_MONTH.getYear(), THIS_MONTH.getMonthValue())
                .orElseThrow();

        Object startDate = entityManager.createNativeQuery(
                        "SELECT start_date FROM employee_records WHERE id = :id")
                .setParameter("id", recordId)
                .getSingleResult();

        assertThat(startDate.toString()).isEqualTo(THIS_MONTH.atDay(1).toString());
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
        LocalDate start = THIS_MONTH.atDay(6);

        employeeService.createEmployee(request(start));
        entityManager.flush();

        Number runs = (Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM payroll_runs WHERE report_year = :y AND report_month = :m")
                .setParameter("y", THIS_MONTH.getYear())
                .setParameter("m", THIS_MONTH.getMonthValue())
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

        // The one karton they have is THIS month's, opened by the return: being
        // hired long ago opened none, and the return does not go looking for the
        // original hire month.
        assertThat(kartonCount(employeeId)).isEqualTo(1);
        assertThat(employeeRecordService.existsForEmployeeAndMonth(
                employeeId, THIS_MONTH.getYear(), THIS_MONTH.getMonthValue())).isTrue();
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

        // Hired long ago, so creation opened none — and the save opens none.
        assertThat(kartonCount(created.getEmployeeId())).isZero();
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

        assertThat(kartonCount(created.getEmployeeId())).isZero();
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
    @Test
    @DisplayName("a rate given at creation is recorded from the start date, so a mid-month hire is priced")
    void aRateAtCreationStartsFromTheHireDate() {
        // Hired on the 22nd. Before this, createEmployee wrote only
        // employees.hourly_rate and no history period, so nothing was in force on
        // the 1st that payroll prices the first month at. The rate now begins on
        // the actual start date.
        LocalDate start = LocalDate.of(2026, 5, 22);
        EmployeeCreateRequest request = request(start);
        request.setHourlyRate(new BigDecimal("480.00"));

        var created = employeeService.createEmployee(request);
        entityManager.flush();

        assertThat(valueService.getHistory(created.getEmployeeId(), EmployeePayrollValueCodes.HOURLY_RATE))
                .singleElement()
                .satisfies(period -> {
                    assertThat(period.getValidFrom()).isEqualTo(start);
                    assertThat(period.getNumericValue()).isEqualByComparingTo("480.00");
                });
    }

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
