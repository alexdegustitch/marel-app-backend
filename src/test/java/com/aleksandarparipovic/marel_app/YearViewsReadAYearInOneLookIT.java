package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.auth.CustomUserDetails;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordSearchHit;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordYearOverview;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRunService;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSearchHit;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollYearOverview;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kartoni and Obračuni year views used to ask thirteen questions per year;
 * now they ask one. These tests pin what that one answer has to contain — the
 * totals, the caller's own trail, the masking — and that the year list and the
 * search find what they should without reading the whole table.
 */
@Transactional
class YearViewsReadAYearInOneLookIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private EmployeeRecordService employeeRecordService;
    @Autowired private PayrollRunService payrollRunService;
    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private WorkShiftService workShiftService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JdbcTemplate jdbc;

    private static final YearMonth APRIL = YearMonth.of(2031, 4);
    private static final YearMonth MAY = YearMonth.of(2031, 5);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ── Kartoni ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a year of kartoni is twelve months, each summed over its own kartoni")
    void kartonYearIsTwelveSummedMonths() {
        var a = fixture.scenario().period(APRIL).build();
        var b = fixture.scenario().period(APRIL).build();
        var c = fixture.scenario().period(MAY).build();

        EmployeeRecordYearOverview overview = employeeRecordService.getYearOverview(APRIL.getYear());

        assertThat(overview.year()).isEqualTo(APRIL.getYear());
        assertThat(overview.months()).extracting(EmployeeRecordYearOverview.MonthOverview::month)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        var april = overview.months().get(3);
        assertThat(april.recordCount()).isEqualTo(2);
        assertThat(april.employeeCount()).isEqualTo(2);
        assertThat(april.totalShiftMinutes())
                .isEqualTo((long) a.monthlyReport().getTotalShiftMinutes() + b.monthlyReport().getTotalShiftMinutes());

        var may = overview.months().get(4);
        assertThat(may.recordCount()).isEqualTo(1);
        assertThat(may.totalShiftMinutes()).isEqualTo((long) c.monthlyReport().getTotalShiftMinutes());

        // A month nobody worked in is present and empty, never missing.
        var march = overview.months().get(2);
        assertThat(march.recordCount()).isZero();
        assertThat(march.recent()).isEmpty();
        assertThat(march.avgPerformanceRate()).isNull();
    }

    @Test
    @DisplayName("each month lists the kartoni the CALLER last had open — not everybody's")
    void recentKartoniAreTheCallersOwn() {
        var mine = fixture.scenario().period(APRIL).build();
        var theirs = fixture.scenario().period(APRIL).build();
        User me = newUser("me");
        User somebodyElse = newUser("other");
        touchedKarton(mine.employeeRecord().getId(), me, OffsetDateTime.now());
        touchedKarton(theirs.employeeRecord().getId(), somebodyElse, OffsetDateTime.now());

        signedInAs(me);
        var april = employeeRecordService.getYearOverview(APRIL.getYear()).months().get(3);

        assertThat(april.recent()).extracting(EmployeeRecordYearOverview.RecentRecord::employeeRecordId)
                .containsExactly(mine.employeeRecord().getId());
        assertThat(april.recent().get(0).employeeName()).isEqualTo(fullNameOf(mine.employee()));
        // Anybody's touch counts for "when was this month last worked on".
        assertThat(april.lastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("a month's recent list stops at three, newest first")
    void recentKartoniStopAtThree() {
        User me = newUser("me");
        var scenarios = List.of(
                fixture.scenario().period(APRIL).build(),
                fixture.scenario().period(APRIL).build(),
                fixture.scenario().period(APRIL).build(),
                fixture.scenario().period(APRIL).build());
        OffsetDateTime base = OffsetDateTime.now().minusHours(4);
        for (int i = 0; i < scenarios.size(); i++) {
            touchedKarton(scenarios.get(i).employeeRecord().getId(), me, base.plusHours(i));
        }

        signedInAs(me);
        var april = employeeRecordService.getYearOverview(APRIL.getYear()).months().get(3);

        assertThat(april.recent()).hasSize(3);
        assertThat(april.recent().get(0).employeeRecordId()).isEqualTo(scenarios.get(3).employeeRecord().getId());
        assertThat(april.recent()).extracting(EmployeeRecordYearOverview.RecentRecord::employeeRecordId)
                .doesNotContain(scenarios.get(0).employeeRecord().getId());
    }

    @Test
    @DisplayName("nobody signed in gets the totals and an empty trail, not an error")
    void noCallerMeansNoTrail() {
        fixture.scenario().period(APRIL).build();

        var april = employeeRecordService.getYearOverview(APRIL.getYear()).months().get(3);

        assertThat(april.recordCount()).isEqualTo(1);
        assertThat(april.recent()).isEmpty();
    }

    @Test
    @DisplayName("a karton is found by a fragment of the worker's name or number, within the year")
    void kartonIsFoundByAFragment() {
        var april = fixture.scenario().period(APRIL).build();
        var lastYear = fixture.scenario().period(APRIL.minusYears(1)).build();
        String fragment = april.employee().getEmployeeNo().substring(3).toLowerCase();

        List<EmployeeRecordSearchHit> hits = employeeRecordService.searchInYear(APRIL.getYear(), fragment, 8);

        assertThat(hits).extracting(EmployeeRecordSearchHit::getEmployeeRecordId)
                .contains(april.employeeRecord().getId())
                .doesNotContain(lastYear.employeeRecord().getId());
        EmployeeRecordSearchHit hit = hits.stream()
                .filter(h -> h.getEmployeeRecordId().equals(april.employeeRecord().getId()))
                .findFirst().orElseThrow();
        assertThat(hit.getMonth()).isEqualTo(4);
        assertThat(hit.getYear()).isEqualTo(APRIL.getYear());
        assertThat(hit.getEmployeeName()).isEqualTo(fullNameOf(april.employee()));

        assertThat(employeeRecordService.searchInYear(APRIL.getYear(), "   ", 8)).isEmpty();
        // A LIKE metacharacter is a character, not a wildcard.
        assertThat(employeeRecordService.searchInYear(APRIL.getYear(), "%", 8)).isEmpty();
    }

    // ── Obračuni ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a year of obračuni counts each month by status and adds up what it pays")
    void payrollYearCountsAndSums() {
        var open = fixture.scenario().period(APRIL).build();
        var done = fixture.scenario().period(APRIL).inRun(open.payrollRun()).build();
        payrollRunItemService.submit(done.item().getId(), null);
        payrollRunItemService.lock(done.item().getId());

        signedInAs(newUser("admin", "admin"));
        PayrollYearOverview overview = payrollRunService.getYearOverview(APRIL.getYear());

        assertThat(overview.amountsVisible()).isTrue();
        assertThat(overview.months()).hasSize(12);
        var april = overview.months().get(3);
        assertThat(april.itemCount()).isEqualTo(2);
        assertThat(april.draftCount()).isEqualTo(1);
        assertThat(april.approvedCount()).isZero();
        assertThat(april.lockedCount()).isEqualTo(1);
        BigDecimal expected = payrollRunItemService.findById(open.item().getId()).getNetPayableAmount()
                .add(payrollRunItemService.findById(done.item().getId()).getNetPayableAmount());
        assertThat(april.totalNetPayable()).isEqualByComparingTo(expected);
        assertThat(april.totalNetEarnings()).isNotNull();
    }

    @Test
    @DisplayName("without payroll access the year shows no sums, no lock, and a locked month counts as ready")
    void payrollYearIsMaskedForTheFloor() {
        var open = fixture.scenario().period(APRIL).build();
        var done = fixture.scenario().period(APRIL).inRun(open.payrollRun()).build();
        payrollRunItemService.submit(done.item().getId(), null);
        payrollRunItemService.lock(done.item().getId());

        signedInAs(newUser("supervisor", "supervisor"));
        PayrollYearOverview overview = payrollRunService.getYearOverview(APRIL.getYear());

        assertThat(overview.amountsVisible()).isFalse();
        var april = overview.months().get(3);
        assertThat(april.itemCount()).isEqualTo(2);
        assertThat(april.draftCount()).isEqualTo(1);
        assertThat(april.approvedCount()).isEqualTo(1);
        assertThat(april.lockedCount()).isNull();
        assertThat(april.totalNetPayable()).isNull();
        assertThat(april.totalNetEarnings()).isNull();
        // An empty month is masked the same way, so the shape never gives the lock away.
        assertThat(overview.months().get(0).lockedCount()).isNull();
    }

    @Test
    @DisplayName("each payroll month lists the obračuni the caller last had open")
    void recentPayrollsAreTheCallersOwn() {
        var mine = fixture.scenario().period(APRIL).build();
        var theirs = fixture.scenario().period(APRIL).inRun(mine.payrollRun()).build();
        User me = newUser("me");
        touchedPayroll(mine.item().getId(), me, OffsetDateTime.now());
        touchedPayroll(theirs.item().getId(), newUser("other"), OffsetDateTime.now());

        signedInAs(me);
        var april = payrollRunService.getYearOverview(APRIL.getYear()).months().get(3);

        assertThat(april.recent()).extracting(PayrollYearOverview.RecentPayroll::monthlyReportId)
                .containsExactly(mine.monthlyReport().getId());
        assertThat(april.recent().get(0).employeeId()).isEqualTo(mine.employee().getId());
        assertThat(april.lastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("a payroll month is found by a fragment, with the status the caller may know")
    void payrollIsFoundByAFragmentWithVisibleStatus() {
        var done = fixture.scenario().period(APRIL).build();
        payrollRunItemService.submit(done.item().getId(), null);
        payrollRunItemService.lock(done.item().getId());
        String fragment = done.employee().getEmployeeNo().substring(3).toLowerCase();

        signedInAs(newUser("admin", "admin"));
        List<PayrollRunSearchHit> seenByPayroll = payrollRunService.searchInYear(APRIL.getYear(), fragment, 8);
        signedInAs(newUser("supervisor", "supervisor"));
        List<PayrollRunSearchHit> seenByFloor = payrollRunService.searchInYear(APRIL.getYear(), fragment, 8);

        PayrollRunSearchHit forPayroll = seenByPayroll.stream()
                .filter(h -> h.monthlyReportId().equals(done.monthlyReport().getId())).findFirst().orElseThrow();
        PayrollRunSearchHit forFloor = seenByFloor.stream()
                .filter(h -> h.monthlyReportId().equals(done.monthlyReport().getId())).findFirst().orElseThrow();
        assertThat(forPayroll.status()).isEqualTo("LOCKED");
        assertThat(forFloor.status()).isEqualTo("APPROVED");
        assertThat(forPayroll.employeeId()).isEqualTo(done.employee().getId());
        assertThat(forPayroll.month()).isEqualTo(4);
    }

    // ── The year list ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the years with shifts are named once each, newest first, however they are found")
    void yearsWithShiftsAreEachNamedOnce() {
        var scenario = fixture.scenario().period(APRIL).build();
        fixture.workShift(scenario.employee(), LocalDate.of(2033, 2, 3), 6, 480);
        fixture.workShift(scenario.employee(), LocalDate.of(2033, 7, 3), 6, 480);
        fixture.workShift(scenario.employee(), LocalDate.of(2035, 1, 3), 6, 480);
        var archived = fixture.workShift(scenario.employee(), LocalDate.of(2037, 1, 3), 6, 480);
        jdbc.update("UPDATE work_shifts SET archived_at = now() WHERE id = ?", archived.getId());

        List<Integer> years = workShiftService.findYearsWithShifts();

        assertThat(years).isSortedAccordingTo((x, y) -> Integer.compare(y, x));
        assertThat(years).doesNotHaveDuplicates();
        assertThat(years).contains(2035, 2033).doesNotContain(2037);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * {@code full_name} is a generated column, and a freshly saved Employee has
     * not read it back — so the name is put together the way the database does.
     */
    private static String fullNameOf(com.aleksandarparipovic.marel_app.employee.Employee employee) {
        return employee.getFirstName() + " " + employee.getLastName();
    }

    private void touchedKarton(Long employeeRecordId, User user, OffsetDateTime at) {
        jdbc.update("""
                INSERT INTO employee_record_updates (employee_record_id, user_id, last_activity_at)
                VALUES (?, ?, ?)
                """, employeeRecordId, user.getId(), at);
    }

    private void touchedPayroll(Long payrollRunItemId, User user, OffsetDateTime at) {
        jdbc.update("""
                INSERT INTO employee_payroll_run_item_updates (payroll_run_item_id, user_id, last_activity_at)
                VALUES (?, ?, ?)
                """, payrollRunItemId, user.getId(), at);
    }

    private User newUser(String prefix) {
        return newUser(prefix, null);
    }

    private User newUser(String prefix, String roleName) {
        int n = COUNTER.incrementAndGet();
        Role role = roleName == null
                ? roleRepository.findAll().stream().findFirst().orElseThrow()
                : roleRepository.findAll().stream()
                        .filter(r -> roleName.equalsIgnoreCase(r.getRoleName()))
                        .findFirst().orElseThrow(() -> new AssertionError("No role " + roleName));
        return userRepository.save(User.builder()
                .username(prefix + "-" + n + "-" + System.nanoTime())
                .passwordHash("x")
                .firstName("Test")
                .lastName(prefix + n)
                .emailAddress(prefix + n + "-" + System.nanoTime() + "@example.rs")
                .role(role)
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build());
    }

    /** The principal the application itself builds, so the user id is readable. */
    private void signedInAs(User user) {
        CustomUserDetails principal = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "p", principal.getAuthorities()));
    }
}
