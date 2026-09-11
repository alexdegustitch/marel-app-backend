package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.shift.EmployeeShiftTimeService;
import com.aleksandarparipovic.marel_app.shift.ShiftAdminService;
import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftTimeRequest;
import com.aleksandarparipovic.marel_app.shift.dto.UpsertShiftRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftCreateRequest;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A worker's own shift hours seed the karton; history stays put.
 *
 * <p>Shift I is 06–14 for the factory, but the worker who standardly comes at
 * 08 works it 08–16 — employee_shift_time_periods says so, dated. Every writer
 * of work_shifts.start_at/end_at goes through {@code ShiftTimeResolver}, and
 * the boundary recalculation resolves FOR THE SHIFT'S OWN DATE, so a default
 * moved today no longer drags last month's boundaries with it.
 */
@Transactional
class EmployeeShiftTimeIT extends AbstractIntegrationTest {

    @Autowired private WorkShiftService workShiftService;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private EmployeeShiftTimeService employeeShiftTimeService;
    @Autowired private ShiftAdminService shiftAdminService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private record Setup(Long employeeId, Long supervisorId, Long categoryId, Long firstShiftId) {}

    @SuppressWarnings("unchecked")
    private Setup setUp() {
        var scenario = fixture.scenario().build();
        Long supervisorId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM users ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
        // Shift definitions are production DATA, not schema — created here so the
        // test states the times it depends on. Same shape as WorkShiftOverlapIT.
        entityManager.createNativeQuery("""
                INSERT INTO shifts (shift_code, name, start_time, end_time, is_active)
                SELECT 'S1', 'Prva smena', TIME '06:00', TIME '14:00', TRUE
                WHERE NOT EXISTS (SELECT 1 FROM shifts x WHERE x.shift_code = 'S1')""")
                .executeUpdate();
        entityManager.flush();
        Long firstShiftId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM shifts WHERE shift_code = 'S1'")
                .getSingleResult()).longValue();
        return new Setup(scenario.employee().getId(), supervisorId,
                scenario.workCategory().getId(), firstShiftId);
    }

    private static void assertMoment(OffsetDateTime actual, String expected) {
        assertThat(actual.toInstant()).isEqualTo(OffsetDateTime.parse(expected).toInstant());
    }

    private WorkShiftCreateRequest createRequest(Setup s, String workDate) {
        WorkShiftCreateRequest req = new WorkShiftCreateRequest();
        req.setEmployeeId(s.employeeId());
        req.setWorkDate(workDate);
        req.setShiftType(s.firstShiftId());
        req.setWorkCategoryCodeId(s.categoryId());
        req.setSupervisorId(s.supervisorId());
        return req;
    }

    private void giveOwnHours(Setup s, String from, String to, String start, String end) {
        ChangeShiftTimeRequest req = new ChangeShiftTimeRequest();
        req.setShiftId(s.firstShiftId());
        req.setStartTime(LocalTime.parse(start));
        req.setEndTime(LocalTime.parse(end));
        req.setValidFrom(LocalDate.parse(from));
        req.setValidTo(to != null ? LocalDate.parse(to) : null);
        employeeShiftTimeService.change(s.employeeId(), req);
    }

    // ── seeding the karton ──────────────────────────────────────────────────

    @Test
    @DisplayName("the worker's own hours seed their shift, not the default")
    void ownHoursSeedTheShift() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", null, "08:00", "16:00");

        var created = workShiftService.createShift(createRequest(s, "2026-09-02"));

        var saved = workShiftRepository.findById(created.id()).orElseThrow();
        assertMoment(saved.getStartAt(), "2026-09-02T08:00+02:00");
        assertMoment(saved.getEndAt(), "2026-09-02T16:00+02:00");
    }

    @Test
    @DisplayName("before the spell begins, the default still applies")
    void beforeTheSpellTheDefaultApplies() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", null, "08:00", "16:00");

        var created = workShiftService.createShift(createRequest(s, "2026-08-25"));

        var saved = workShiftRepository.findById(created.id()).orElseThrow();
        assertMoment(saved.getStartAt(), "2026-08-25T06:00+02:00");
        assertMoment(saved.getEndAt(), "2026-08-25T14:00+02:00");
    }

    @Test
    @DisplayName("after the spell's valid_to, the default applies again")
    void afterTheSpellTheDefaultReturns() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", "2026-09-10", "08:00", "16:00");

        var created = workShiftService.createShift(createRequest(s, "2026-09-11"));

        var saved = workShiftRepository.findById(created.id()).orElseThrow();
        assertMoment(saved.getStartAt(), "2026-09-11T06:00+02:00");
        assertMoment(saved.getEndAt(), "2026-09-11T14:00+02:00");
    }

    @Test
    @DisplayName("own hours over midnight span into the next day, like shift III")
    void ownHoursMayWrapMidnight() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", null, "21:00", "05:00");

        var created = workShiftService.createShift(createRequest(s, "2026-09-02"));

        var saved = workShiftRepository.findById(created.id()).orElseThrow();
        assertMoment(saved.getStartAt(), "2026-09-02T21:00+02:00");
        assertMoment(saved.getEndAt(), "2026-09-03T05:00+02:00");
    }

    // ── the spells themselves ───────────────────────────────────────────────

    @Test
    @DisplayName("a new spell closes the open one the day before it begins")
    void aNewSpellClosesTheOpenOne() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", null, "08:00", "16:00");
        giveOwnHours(s, "2026-10-01", null, "07:00", "15:00");

        var history = employeeShiftTimeService.history(s.employeeId());
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().validFrom()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(history.getFirst().validTo()).isNull();
        assertThat(history.getLast().validTo()).isEqualTo(LocalDate.parse("2026-09-30"));

        // Each month's karton is seeded by its own spell.
        var september = workShiftRepository.findById(
                workShiftService.createShift(createRequest(s, "2026-09-15")).id()).orElseThrow();
        assertMoment(september.getStartAt(), "2026-09-15T08:00+02:00");
        var october = workShiftRepository.findById(
                workShiftService.createShift(createRequest(s, "2026-10-15")).id()).orElseThrow();
        assertMoment(october.getStartAt(), "2026-10-15T07:00+02:00");
    }

    @Test
    @DisplayName("a spell starting before the current one is refused")
    void aSpellCannotStartBeforeTheCurrentOne() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", null, "08:00", "16:00");

        assertThatThrownBy(() -> giveOwnHours(s, "2026-08-15", null, "07:00", "15:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posle početka");
    }

    // ── history stays put when the default moves ────────────────────────────

    @Test
    @DisplayName("moving the default forward does not rewrite an old shift's baseline")
    void aMovedDefaultLeavesOldBoundariesAlone() {
        Setup s = setUp();
        var created = workShiftService.createShift(createRequest(s, "2026-09-02"));

        // The šifarnik moves shift I to 07–15 from October.
        UpsertShiftRequest change = new UpsertShiftRequest();
        change.setShiftCode("S1");
        change.setName("Prva smena");
        change.setStartTime(LocalTime.parse("07:00"));
        change.setEndTime(LocalTime.parse("15:00"));
        change.setEffectiveFrom(LocalDate.parse("2026-10-01"));
        shiftAdminService.update(s.firstShiftId(), change);

        // Touching the September shift's boundaries re-resolves FOR ITS DATE —
        // the closed default spell answers 06–14, not the new row's 07–15.
        var shift = workShiftRepository.findById(created.id()).orElseThrow();
        workShiftService.recalculateShiftBoundaries(shift);
        var after = workShiftRepository.findById(created.id()).orElseThrow();
        assertMoment(after.getStartAt(), "2026-09-02T06:00+02:00");
        assertMoment(after.getEndAt(), "2026-09-02T14:00+02:00");

        // While a NEW shift in October is seeded with the new default.
        var october = workShiftRepository.findById(
                workShiftService.createShift(createRequest(s, "2026-10-05")).id()).orElseThrow();
        assertMoment(october.getStartAt(), "2026-10-05T07:00+02:00");
        assertMoment(october.getEndAt(), "2026-10-05T15:00+02:00");
    }

    @Test
    @DisplayName("the exclusion constraint refuses overlapping spells underneath")
    @SuppressWarnings("unchecked")
    void theConstraintStillGuards() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", "2026-09-20", "08:00", "16:00");

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO employee_shift_time_periods
                        (employee_id, shift_id, start_time, end_time, valid_from, valid_to)
                    VALUES (:emp, :shift, TIME '09:00', TIME '17:00', DATE '2026-09-10', NULL)""")
                    .setParameter("emp", s.employeeId())
                    .setParameter("shift", s.firstShiftId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("ex_estp_no_overlap");
    }

    @Test
    @DisplayName("when each shift runs, answered per employee: own hours marked as such")
    void effectiveTimesAnswerPerEmployee() {
        Setup s = setUp();
        giveOwnHours(s, "2026-09-01", null, "08:00", "16:00");

        List<com.aleksandarparipovic.marel_app.shift.dto.EffectiveShiftTimeDto> effective =
                employeeShiftTimeService.effective(s.employeeId(), LocalDate.parse("2026-09-15"));

        var first = effective.stream()
                .filter(e -> e.shiftId().equals(s.firstShiftId()))
                .findFirst().orElseThrow();
        assertThat(first.startTime()).isEqualTo(LocalTime.parse("08:00"));
        assertThat(first.endTime()).isEqualTo(LocalTime.parse("16:00"));
        assertThat(first.employeeOwn()).isTrue();
    }
}
