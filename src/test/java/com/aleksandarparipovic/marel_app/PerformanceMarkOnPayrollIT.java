package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.auth.CustomUserDetails;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMark;
import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMarkAmountUnit;
import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMarkRepository;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ocena on a payroll item is CHOSEN from the šifarnik (V58), no longer
 * typed. What this must guarantee, against the real schema: the chosen version
 * must be in force on the payroll month's first day; choosing replaces the
 * legacy typed multiplier; a chooser who also holds PAYROLL_MARK_APPLY puts
 * the mark in force in the same call (the owner's rule), while a chooser with
 * only PAYROLL_MARK_EDIT still moves no money; the applied mark adjusts the
 * rate by the version's amount (percent of the base, or dinars per hour)
 * floored at zero; and reverting returns exactly to the base.
 */
@Transactional
class PerformanceMarkOnPayrollIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService service;
    @Autowired private PayrollRunItemRepository itemRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private PerformanceMarkRepository markRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger(500);
    /** The fixture's default month is 2026-09; its first day is what validity is checked against. */
    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);

    /** Most tests choose as the administrator, who holds both halves. */
    @BeforeEach
    void signIn() {
        signedInAs("admin");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The principal the application itself builds, with a REAL role, so PermissionService answers truthfully. */
    private void signedInAs(String roleName) {
        int n = COUNTER.incrementAndGet();
        User user = userRepository.save(User.builder()
                .username("marker-" + n + "-" + System.nanoTime())
                .passwordHash("x")
                .firstName("Test")
                .lastName("Marker" + n)
                .emailAddress("marker" + n + "-" + System.nanoTime() + "@example.rs")
                .role(roleRepository.findAll().stream()
                        .filter(r -> roleName.equalsIgnoreCase(r.getRoleName()))
                        .findFirst().orElseThrow(() -> new AssertionError("No role " + roleName)))
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build());
        CustomUserDetails principal = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "p", principal.getAuthorities()));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private PerformanceMark sifarnikMark(String amount, PerformanceMarkAmountUnit unit,
                                         LocalDate validFrom, LocalDate validTo) {
        return markRepository.saveAndFlush(PerformanceMark.builder()
                .mark(BigDecimal.valueOf(COUNTER.incrementAndGet()))
                .amount(new BigDecimal(amount))
                .amountUnit(unit)
                .validFrom(validFrom)
                .validTo(validTo)
                .build());
    }

    private PayrollRunItem reload(Long id) {
        return itemRepository.findById(id).orElseThrow();
    }

    // ── choosing ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an APPLY holder's choice goes in force in the same call")
    void adminChoiceAppliesImmediately() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        PerformanceMark mark = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);

        service.setPerformanceMark(scenario.item().getId(), mark.getId());

        PayrollRunItem item = reload(scenario.item().getId());
        assertThat(item.getPerformanceMarkRef().getId()).isEqualTo(mark.getId());
        assertThat(item.getPerformanceMark()).isNull();
        assertThat(item.getPerformanceMarkApplied()).isTrue();
        assertThat(item.getPerformanceMarkBy()).isNotNull();
        assertThat(item.getPerformanceMarkAppliedBy()).isNotNull();
        assertThat(item.getHourlyRate()).isEqualByComparingTo("375.00");
    }

    @Test
    @DisplayName("an EDIT-only chooser records the mark and moves no money")
    void supervisorChoiceDoesNotApply() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        PerformanceMark mark = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);

        signedInAs("supervisor");
        service.setPerformanceMark(scenario.item().getId(), mark.getId());

        PayrollRunItem item = reload(scenario.item().getId());
        assertThat(item.getPerformanceMarkRef().getId()).isEqualTo(mark.getId());
        assertThat(item.getPerformanceMarkApplied()).isFalse();
        assertThat(item.getPerformanceMarkAppliedBy()).isNull();
        assertThat(item.getHourlyRate()).isEqualByComparingTo("420.00");
    }

    @Test
    @DisplayName("a mark whose validity misses the month's first day is refused")
    void aMarkOutsideTheMonthIsRefused() {
        var scenario = fixture.scenario().build();
        PerformanceMark future = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                MONTH_START.plusMonths(1), null);
        PerformanceMark expired = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), MONTH_START.minusDays(1));

        assertThatThrownBy(() -> service.setPerformanceMark(scenario.item().getId(), future.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ne važi za ovaj mesec");
        assertThatThrownBy(() -> service.setPerformanceMark(scenario.item().getId(), expired.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ne važi za ovaj mesec");
    }

    @Test
    @DisplayName("an archived mark is refused even when its window covers the month")
    void anArchivedMarkIsRefused() {
        var scenario = fixture.scenario().build();
        PerformanceMark archived = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);
        archived.setArchivedAt(OffsetDateTime.now());
        markRepository.saveAndFlush(archived);

        assertThatThrownBy(() -> service.setPerformanceMark(scenario.item().getId(), archived.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ne važi za ovaj mesec");
    }

    @Test
    @DisplayName("choosing replaces a legacy typed multiplier — V58's one-kind rule")
    void choosingReplacesALegacyMark() {
        var scenario = fixture.scenario().build();
        PayrollRunItem item = reload(scenario.item().getId());
        item.setPerformanceMark(new BigDecimal("1.10"));
        item.setPerformanceMarkBy(userRepository.findAll().get(0));
        item.setPerformanceMarkAt(OffsetDateTime.now());
        itemRepository.saveAndFlush(item);

        PerformanceMark mark = sifarnikMark("10.00", PerformanceMarkAmountUnit.PERCENT,
                LocalDate.of(2026, 1, 1), null);
        service.setPerformanceMark(item.getId(), mark.getId());

        PayrollRunItem after = reload(item.getId());
        assertThat(after.getPerformanceMark()).isNull();
        assertThat(after.getPerformanceMarkRef().getId()).isEqualTo(mark.getId());
    }

    // ── applying ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("revert is exact, and the two-step apply still works after it")
    void applyAndRevertRsdPerHour() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        PerformanceMark mark = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);
        service.setPerformanceMark(scenario.item().getId(), mark.getId());
        assertThat(reload(scenario.item().getId()).getHourlyRate()).isEqualByComparingTo("375.00");

        service.revertPerformanceMark(scenario.item().getId());
        assertThat(reload(scenario.item().getId()).getHourlyRate()).isEqualByComparingTo("420.00");

        // The standalone apply endpoint survives for the supervisor-set case.
        service.applyPerformanceMark(scenario.item().getId());
        assertThat(reload(scenario.item().getId()).getHourlyRate()).isEqualByComparingTo("375.00");
    }

    @Test
    @DisplayName("a PERCENT mark moves the base by a share of itself")
    void applyPercent() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        PerformanceMark mark = sifarnikMark("-40.00", PerformanceMarkAmountUnit.PERCENT,
                LocalDate.of(2026, 1, 1), null);

        service.setPerformanceMark(scenario.item().getId(), mark.getId());

        assertThat(reload(scenario.item().getId()).getHourlyRate()).isEqualByComparingTo("252.00");
    }

    @Test
    @DisplayName("a deduction larger than the rate pays 0, never a negative hour")
    void deductionFloorsAtZero() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        PerformanceMark mark = sifarnikMark("-500.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);

        service.setPerformanceMark(scenario.item().getId(), mark.getId());

        assertThat(reload(scenario.item().getId()).getHourlyRate()).isEqualByComparingTo("0.00");
    }

    // ── the history on the detail response ──────────────────────────────────

    /**
     * The fixture builds a fresh employee per scenario, so the earlier months are
     * re-pointed at the current month's employee — the history query reads
     * {@code payroll_run_items.employee_id} and nothing else.
     */
    private void reassignTo(com.aleksandarparipovic.marel_app.employee.Employee employee, Long itemId) {
        PayrollRunItem item = reload(itemId);
        item.setEmployee(employee);
        itemRepository.saveAndFlush(item);
    }

    /** Finishes a month AFTER its mark was set — a LOCKED item refuses mark writes. */
    private void lock(Long itemId) {
        PayrollRunItem item = reload(itemId);
        item.setStatus("LOCKED");
        itemRepository.saveAndFlush(item);
    }

    @Test
    @DisplayName("the history carries every LOCKED month of the window, marked or not, and nothing else")
    void detailCarriesMarkHistory() {
        var current = fixture.scenario().hourlyRate("420.00").build();
        // Window for 2026-09: April–August. March is one month too old.
        var march = fixture.scenario().period(java.time.YearMonth.of(2026, 3)).build();
        var may = fixture.scenario().period(java.time.YearMonth.of(2026, 5)).build();
        var june = fixture.scenario().period(java.time.YearMonth.of(2026, 6)).build();
        var july = fixture.scenario().period(java.time.YearMonth.of(2026, 7)).build();
        var august = fixture.scenario().period(java.time.YearMonth.of(2026, 8)).build();
        for (var scenario : java.util.List.of(march, may, june, july, august)) {
            reassignTo(current.employee(), scenario.item().getId());
        }

        // June carries an APPLIED legacy typed multiplier — the pre-šifarnik
        // kind. Signed in full, as the V58 attribution CHECKs insist.
        User signer = userRepository.findAll().get(0);
        PayrollRunItem juneItem = reload(june.item().getId());
        juneItem.setPerformanceMark(new BigDecimal("1.50"));
        juneItem.setPerformanceMarkBy(signer);
        juneItem.setPerformanceMarkAt(OffsetDateTime.now());
        juneItem.setPerformanceMarkApplied(true);
        juneItem.setPerformanceMarkAppliedBy(signer);
        juneItem.setPerformanceMarkAppliedAt(OffsetDateTime.now());
        itemRepository.saveAndFlush(juneItem);

        // July's šifarnik mark goes in force (the admin holds APPLY); March gets
        // one too, but falls outside the six-month window.
        PerformanceMark julyMark = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);
        service.setPerformanceMark(july.item().getId(), julyMark.getId());
        PerformanceMark marchMark = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);
        service.setPerformanceMark(march.item().getId(), marchMark.getId());

        // August's is chosen by an EDIT-only supervisor and never applied — the
        // finished month is still listed, with a null mark (an empty column).
        PerformanceMark augustMark = sifarnikMark("10.00", PerformanceMarkAmountUnit.PERCENT,
                LocalDate.of(2026, 1, 1), null);
        signedInAs("supervisor");
        service.setPerformanceMark(august.item().getId(), augustMark.getId());
        signedInAs("admin");

        // Everything but May is finished. May stays DRAFT with no finished
        // obračun, so its slot on the chart has no month under it.
        for (Long id : java.util.List.of(march.item().getId(), june.item().getId(),
                july.item().getId(), august.item().getId())) {
            lock(id);
        }

        var detail = service.getDetails(current.monthlyReport().getId());

        assertThat(detail.getMarkHistory())
                .extracting(h -> h.getPeriod())
                .containsExactly(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1));
        assertThat(detail.getMarkHistory().get(0).getMark()).isEqualByComparingTo("1.50");
        assertThat(detail.getMarkHistory().get(1).getMark()).isEqualByComparingTo(julyMark.getMark());
        assertThat(detail.getMarkHistory().get(2).getMark()).isNull();
    }

    // ── taking away ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a null markId takes the mark away, and an applied one stops being in force")
    void nullTakesTheMarkAway() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        PerformanceMark mark = sifarnikMark("-45.00", PerformanceMarkAmountUnit.RSD_PER_HOUR,
                LocalDate.of(2026, 1, 1), null);
        // The admin's choice is already in force; taking it away must undo that.
        service.setPerformanceMark(scenario.item().getId(), mark.getId());
        assertThat(reload(scenario.item().getId()).getHourlyRate()).isEqualByComparingTo("375.00");

        service.setPerformanceMark(scenario.item().getId(), null);

        PayrollRunItem after = reload(scenario.item().getId());
        assertThat(after.getPerformanceMarkRef()).isNull();
        assertThat(after.getPerformanceMarkApplied()).isFalse();
        assertThat(after.getHourlyRate()).isEqualByComparingTo("420.00");
    }
}
