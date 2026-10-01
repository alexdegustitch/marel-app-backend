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
