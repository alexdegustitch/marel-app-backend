package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.auth.CustomUserDetails;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.payroll_change_request.PayrollChangeRequestService;
import com.aleksandarparipovic.marel_app.payroll_change_request.dto.PayrollChangeRequestCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRunService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemHandover;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemHandoverRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The handover to payroll — the SENT step V72 put between APPROVED and LOCKED.
 *
 * <p>What is pinned here is the part the workflow depends on: that a month goes
 * over only complete (no drafts, no employed worker without an obračun), that
 * one payroll can go alone but only with a reason, that the director reads
 * nothing before the handover and everything after it, and that each step
 * leaves its own record.
 */
@Transactional
class PayrollSendIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollRunService payrollRunService;
    @Autowired private PayrollChangeRequestService changeRequestService;
    @Autowired private PayrollRunItemHandoverRepository handoverRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    /** A month no other test seeds, so coverage checks count only this test's workers. */
    private static final YearMonth PERIOD = YearMonth.of(2032, 3);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private User newUser(String prefix, String roleName) {
        int n = COUNTER.incrementAndGet();
        Role role = roleRepository.findAll().stream()
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

    private void signedInAs(User user) {
        CustomUserDetails principal = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "p", principal.getAuthorities()));
    }

    private List<PayrollRunItemHandover> handoversOf(Long itemId) {
        entityManager.flush();
        return handoverRepository.findByPayrollRunItemIdOrderByOccurredAtDesc(itemId);
    }

    // ── The chain itself ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the chain is DRAFT → APPROVED → SENT → LOCKED, and no step can be skipped")
    void theChainHasFourSteps() {
        var scenario = fixture.scenario().period(PERIOD).build();
        Long id = scenario.item().getId();

        // Sending a draft is refused — only an approved month has anything to send.
        assertThatThrownBy(() -> payrollRunItemService.sendOne(id, "razlog"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("odobren");

        payrollRunItemService.submit(id, null);
        assertThatThrownBy(() -> payrollRunItemService.lock(id))
                .hasMessageContaining("predat");

        var sent = payrollRunItemService.sendOne(id, "radnik odlazi, treba ranije");
        assertThat(sent.getStatus()).isEqualTo("SENT");
        assertThat(sent.getSentAt()).isNotNull();

        assertThat(payrollRunItemService.lock(id).getStatus()).isEqualTo("LOCKED");

        List<PayrollRunItemHandover> steps = handoversOf(id);
        assertThat(steps.stream().map(PayrollRunItemHandover::getEvent))
                .containsExactly("LOCKED", "SENT", "APPROVED");
        assertThat(steps.get(1).getNote()).isEqualTo("radnik odlazi, treba ranije");
        assertThat(steps.get(1).getStatusBefore()).isEqualTo("APPROVED");
        assertThat(steps.get(1).getStatusAfter()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("one payroll travels alone only with a reason")
    void singleSendDemandsAReason() {
        var scenario = fixture.scenario().period(PERIOD).build();
        Long id = scenario.item().getId();
        payrollRunItemService.submit(id, null);

        assertThatThrownBy(() -> payrollRunItemService.sendOne(id, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("komentar");
        assertThatThrownBy(() -> payrollRunItemService.sendOne(id, "   "))
                .isInstanceOf(ConflictException.class);

        assertThat(payrollRunItemService.findById(id).getStatus()).isEqualTo("APPROVED");
    }

    // ── The month as a whole ─────────────────────────────────────────────────

    @Test
    @DisplayName("the month goes over only once every obračun is approved")
    void sendMonthRefusesDrafts() {
        var first = fixture.scenario().period(PERIOD).build();
        fixture.scenario().period(PERIOD).inRun(first.payrollRun()).build(); // stays DRAFT
        payrollRunItemService.submit(first.item().getId(), null);

        assertThatThrownBy(() -> payrollRunItemService.sendMonth(PERIOD.getYear(), PERIOD.getMonthValue()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("odobreni");

        assertThat(payrollRunItemService.findById(first.item().getId()).getStatus())
                .isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("sending the month moves every approved obračun and records a step on each")
    void sendMonthMovesEverything() {
        var first = fixture.scenario().period(PERIOD).build();
        var second = fixture.scenario().period(PERIOD).inRun(first.payrollRun()).build();
        payrollRunItemService.submit(first.item().getId(), null);
        payrollRunItemService.submit(second.item().getId(), null);

        int sent = payrollRunItemService.sendMonth(PERIOD.getYear(), PERIOD.getMonthValue());

        assertThat(sent).isEqualTo(2);
        assertThat(payrollRunItemService.findById(first.item().getId()).getStatus()).isEqualTo("SENT");
        assertThat(payrollRunItemService.findById(second.item().getId()).getStatus()).isEqualTo("SENT");
        assertThat(handoversOf(first.item().getId()).getFirst().getEvent()).isEqualTo("SENT");

        // Idempotent for what is already over: nothing left to send is a refusal,
        // not a second handover.
        assertThatThrownBy(() -> payrollRunItemService.sendMonth(PERIOD.getYear(), PERIOD.getMonthValue()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Nema odobrenih");
    }

    @Test
    @DisplayName("an employed worker without an obračun blocks the month")
    void sendMonthRefusesAMissingWorker() {
        var scenario = fixture.scenario().period(PERIOD).build();
        payrollRunItemService.submit(scenario.item().getId(), null);

        // The same worker exists, employed over the month, but their obračun is
        // archived — exactly the "looks complete, somebody missing" case.
        jdbc.update("UPDATE payroll_run_items SET archived_at = now() WHERE id = ?",
                scenario.item().getId());

        assertThatThrownBy(() -> payrollRunItemService.sendMonth(PERIOD.getYear(), PERIOD.getMonthValue()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("bez obračuna")
                .hasMessageContaining("Kreiraj kartone");
    }

    @Test
    @DisplayName("readiness answers the button: what blocks, and who is missing")
    void readinessNamesTheBlockers() {
        var first = fixture.scenario().period(PERIOD).build();
        fixture.scenario().period(PERIOD).inRun(first.payrollRun()).build(); // DRAFT

        var notReady = payrollRunService.getReadiness(PERIOD.getYear(), PERIOD.getMonthValue());
        assertThat(notReady.readyToSend()).isFalse();
        assertThat(notReady.draftCount()).isEqualTo(2);

        payrollRunItemService.submit(first.item().getId(), null);
        var stillNotReady = payrollRunService.getReadiness(PERIOD.getYear(), PERIOD.getMonthValue());
        assertThat(stillNotReady.draftCount()).isEqualTo(1);
        assertThat(stillNotReady.approvedCount()).isEqualTo(1);
        assertThat(stillNotReady.readyToSend()).isFalse();
    }

    // ── Who may take which step back ─────────────────────────────────────────

    @Test
    @DisplayName("the supervisor pulls back their own approval; a sent month is payroll's")
    void returnFollowsTheOwner() {
        var scenario = fixture.scenario().period(PERIOD).build();
        Long id = scenario.item().getId();
        payrollRunItemService.submit(id, null);

        // Before the handover the month is still the shop floor's.
        signedInAs(newUser("sup", "supervisor"));
        assertThat(payrollRunItemService.returnToDraft(id, "fali smena").getStatus())
                .isEqualTo("DRAFT");
        assertThat(payrollRunItemService.findById(id).getApprovedAt()).isNull();

        payrollRunItemService.submit(id, null);
        SecurityContextHolder.clearContext();
        payrollRunItemService.sendOne(id, "ide ranije");

        // After it, the supervisor may only ask.
        signedInAs(newUser("sup", "supervisor"));
        assertThatThrownBy(() -> payrollRunItemService.returnToDraft(id, "ipak ne"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("zahtev");

        // Payroll hands it back, and the stamps go with it.
        signedInAs(newUser("dir", "admin"));
        assertThat(payrollRunItemService.returnToDraft(id, "fali prekovremeni").getStatus())
                .isEqualTo("DRAFT");
        var item = payrollRunItemService.findById(id);
        assertThat(item.getSentAt()).isNull();
        assertThat(item.getSentBy()).isNull();
    }

    @Test
    @DisplayName("a change request is for a sent or locked month, no longer for an approved one")
    void changeRequestsStartAtSent() {
        var scenario = fixture.scenario().period(PERIOD).build();
        Long id = scenario.item().getId();
        payrollRunItemService.submit(id, null);

        signedInAs(newUser("sup", "supervisor"));
        var request = new PayrollChangeRequestCreateRequest();
        request.setPayrollRunItemId(id);
        request.setReason("fali prekovremeni rad");
        assertThatThrownBy(() -> changeRequestService.create(request))
                .isInstanceOf(ConflictException.class);

        SecurityContextHolder.clearContext();
        payrollRunItemService.sendOne(id, "ide ranije");

        signedInAs(newUser("sup", "supervisor"));
        assertThat(changeRequestService.create(request)).isNotNull();
    }

    // ── What the director reads ──────────────────────────────────────────────

    @Test
    @DisplayName("the director reads nothing before the handover, and everything after it")
    void directorSeesOnlySent() {
        var scenario = fixture.scenario().period(PERIOD).build();
        Long monthlyReportId = scenario.monthlyReport().getId();
        Long id = scenario.item().getId();

        signedInAs(newUser("dir", "admin"));
        assertThatThrownBy(() -> payrollRunItemService.getDetails(monthlyReportId, null))
                .isInstanceOf(AccessDeniedException.class);

        SecurityContextHolder.clearContext();
        payrollRunItemService.submit(id, null);

        signedInAs(newUser("dir", "admin"));
        assertThatThrownBy(() -> payrollRunItemService.getDetails(monthlyReportId, null))
                .isInstanceOf(AccessDeniedException.class);

        SecurityContextHolder.clearContext();
        payrollRunItemService.sendOne(id, "ide ranije");

        signedInAs(newUser("dir", "admin"));
        assertThat(payrollRunItemService.getDetails(monthlyReportId, null)).isNotNull();

        // The developer seat keeps the full view — it is engineering, not payroll.
        SecurityContextHolder.clearContext();
        var draft = fixture.scenario().period(PERIOD).inRun(scenario.payrollRun()).build();
        signedInAs(newUser("dev", "developer"));
        assertThat(payrollRunItemService.getDetails(draft.monthlyReport().getId(), null)).isNotNull();
    }

    @Test
    @DisplayName("the director's month list holds only sent and locked rows")
    void directorsListIsFiltered() {
        var first = fixture.scenario().period(PERIOD).build();
        var second = fixture.scenario().period(PERIOD).inRun(first.payrollRun()).build();
        payrollRunItemService.submit(first.item().getId(), null);
        payrollRunItemService.sendOne(first.item().getId(), "ide ranije");
        entityManager.flush();

        signedInAs(newUser("dir", "admin"));
        var page = payrollRunService.getPagedByYearAndMonth(
                PERIOD.getYear(), PERIOD.getMonthValue(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(page.getContent())
                .extracting(r -> r.getId())
                .contains(first.item().getId())
                .doesNotContain(second.item().getId());

        // The supervisor keeps the whole month — it is their work in progress.
        signedInAs(newUser("sup", "supervisor"));
        var floorPage = payrollRunService.getPagedByYearAndMonth(
                PERIOD.getYear(), PERIOD.getMonthValue(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(floorPage.getContent()).extracting(r -> r.getId())
                .contains(first.item().getId(), second.item().getId());
    }

    @Test
    @DisplayName("the sent list serves the director sorted by worker number or by approval time")
    void sentListSortsBothWays() {
        var first = fixture.scenario().period(PERIOD).build();
        var second = fixture.scenario().period(PERIOD).inRun(first.payrollRun()).build();
        payrollRunItemService.submit(first.item().getId(), null);
        payrollRunItemService.submit(second.item().getId(), null);
        payrollRunItemService.sendMonth(PERIOD.getYear(), PERIOD.getMonthValue());
        entityManager.flush();

        var byNumber = payrollRunService.getSentByYearAndMonth(
                PERIOD.getYear(), PERIOD.getMonthValue(), "employeeNo", "asc", 0, 50);
        assertThat(byNumber.getTotalElements()).isEqualTo(2);
        assertThat(byNumber.getContent().get(0).getEmployeeNo())
                .isLessThanOrEqualTo(byNumber.getContent().get(1).getEmployeeNo());
        assertThat(byNumber.getContent().get(0).getApprovedAt()).isNotNull();
        assertThat(byNumber.getContent().get(0).getSentAt()).isNotNull();

        var byApproval = payrollRunService.getSentByYearAndMonth(
                PERIOD.getYear(), PERIOD.getMonthValue(), "approvedAt", "desc", 0, 50);
        assertThat(byApproval.getContent()).hasSize(2);
        // An unknown sort key falls back to the worker number instead of leaking
        // anything user-typed into an ORDER BY.
        assertThat(payrollRunService.getSentByYearAndMonth(
                PERIOD.getYear(), PERIOD.getMonthValue(), "; DROP TABLE x", "asc", 0, 50)
                .getTotalElements()).isEqualTo(2);
    }

    // ── The notification ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the handover writes one outbox event, addressed at payroll")
    void sendPublishesOneEvent() {
        var first = fixture.scenario().period(PERIOD).build();
        var second = fixture.scenario().period(PERIOD).inRun(first.payrollRun()).build();
        payrollRunItemService.submit(first.item().getId(), null);
        payrollRunItemService.submit(second.item().getId(), null);

        payrollRunItemService.sendMonth(PERIOD.getYear(), PERIOD.getMonthValue());
        entityManager.flush();

        Integer monthEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYROLL_MONTH_SENT'",
                Integer.class);
        // ONE event for the whole month — the director gets one mail, not thirty.
        assertThat(monthEvents).isEqualTo(1);

        Integer count = jdbc.queryForObject(
                "SELECT (payload->>'count')::int FROM outbox_events WHERE event_type = 'PAYROLL_MONTH_SENT'",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
