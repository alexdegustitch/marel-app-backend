package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordMissing;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeWithoutRecord;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Bez kartona" — the workers a month holds no karton for.
 *
 * <p>The month screen states this count and offers the button that fixes it, so
 * the number has to mean exactly what the button would do: it counts the same
 * active workers {@code createEmployeeRecordsForMonth} creates for. These tests
 * pin that agreement, and the native query behind it — a wrong join or a missed
 * {@code is_active} shows up here as a number, not as a crash.
 *
 * <p>Counts are asserted as DIFFERENCES against a baseline read: the register a
 * test runs against is whatever the migrations and the other fixtures left
 * behind, and an absolute count would be pinning that instead of this query.
 */
@Transactional
class WorkersWithoutAKartonIT extends AbstractIntegrationTest {

    @Autowired private EmployeeRecordService employeeRecordService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private JdbcTemplate jdbc;

    private static final YearMonth APRIL = YearMonth.of(2032, 4);
    private static final YearMonth MAY = YearMonth.of(2032, 5);

    private EmployeeRecordMissing missingInApril() {
        return employeeRecordService.getEmployeesWithoutRecord(APRIL.getYear(), APRIL.getMonthValue(), null, 50);
    }

    @Test
    @DisplayName("a worker with no karton for the month is counted and named")
    void countsAndNamesTheWorkerWithoutAKarton() {
        long before = missingInApril().total();

        // A karton in May is not a karton in April: this worker is missing here.
        var may = fixture.scenario().period(MAY).build();

        EmployeeRecordMissing missing = missingInApril();

        assertThat(missing.total()).isEqualTo(before + 1);
        assertThat(missing.rows())
                .extracting(EmployeeWithoutRecord::getEmployeeId)
                .contains(may.employee().getId());

        EmployeeWithoutRecord row = missing.rows().stream()
                .filter(r -> r.getEmployeeId().equals(may.employee().getId()))
                .findFirst()
                .orElseThrow();

        // The identity columns the month list shows beside a karton row.
        assertThat(row.getEmployeeNo()).isEqualTo(may.employee().getEmployeeNo());
        assertThat(row.getEmployeeName()).isEqualTo(may.employee().getFullName());
        assertThat(row.getEmployeeDepartment()).isEqualTo(may.employee().getDepartment().getName());
    }

    @Test
    @DisplayName("a worker who already has the month's karton is not among them")
    void leavesOutTheWorkerWhoHasAKarton() {
        var april = fixture.scenario().period(APRIL).build();

        assertThat(missingInApril().rows())
                .extracting(EmployeeWithoutRecord::getEmployeeId)
                .doesNotContain(april.employee().getId());
    }

    @Test
    @DisplayName("creating the month's kartoni empties the list it was counting")
    void creatingTheKartoniClearsTheCount() {
        fixture.scenario().period(MAY).build();
        assertThat(missingInApril().total()).isPositive();

        employeeRecordService.createEmployeeRecordsForMonth(APRIL.getYear(), APRIL.getMonthValue());

        // What the button creates is what the count was counting — all of it.
        assertThat(missingInApril().total()).isZero();
        assertThat(missingInApril().rows()).isEmpty();
    }

    @Test
    @DisplayName("an inactive worker is not missing a karton — nobody would create one")
    void ignoresAnInactiveWorker() {
        var may = fixture.scenario().period(MAY).build();
        long withThem = missingInApril().total();

        jdbc.update("UPDATE employees SET is_active = false WHERE id = ?", may.employee().getId());

        assertThat(missingInApril().total()).isEqualTo(withThem - 1);
    }

    @Test
    @DisplayName("an archived worker is not missing a karton either")
    void ignoresAnArchivedWorker() {
        var may = fixture.scenario().period(MAY).build();
        long withThem = missingInApril().total();

        jdbc.update("UPDATE employees SET archived_at = now() WHERE id = ?", may.employee().getId());

        assertThat(missingInApril().total()).isEqualTo(withThem - 1);
    }

    @Test
    @DisplayName("the month's search narrows who is missing, so a filtered list stays one list")
    void searchNarrowsTheMissingList() {
        var may = fixture.scenario().period(MAY).build();

        EmployeeRecordMissing byNumber = employeeRecordService.getEmployeesWithoutRecord(
                APRIL.getYear(), APRIL.getMonthValue(), may.employee().getEmployeeNo(), 50);

        assertThat(byNumber.total()).isEqualTo(1);
        assertThat(byNumber.rows()).singleElement()
                .extracting(EmployeeWithoutRecord::getEmployeeId)
                .isEqualTo(may.employee().getId());

        EmployeeRecordMissing byNobody = employeeRecordService.getEmployeesWithoutRecord(
                APRIL.getYear(), APRIL.getMonthValue(), "nikonijetakoprezime", 50);

        assertThat(byNobody.total()).isZero();
        assertThat(byNobody.rows()).isEmpty();
    }

    /**
     * The whole register is missing a karton for a month nobody has opened yet.
     * The list is capped so that answer stays an answer; the count is not, because
     * the count is what the screen states and what the button acts on.
     */
    @Test
    @DisplayName("the list is capped, the count is not")
    void capsTheListButNotTheCount() {
        fixture.scenario().period(MAY).build();
        fixture.scenario().period(MAY).build();

        EmployeeRecordMissing capped = employeeRecordService.getEmployeesWithoutRecord(
                APRIL.getYear(), APRIL.getMonthValue(), null, 1);

        assertThat(capped.rows()).hasSize(1);
        assertThat(capped.total()).isGreaterThanOrEqualTo(2);
    }
}
