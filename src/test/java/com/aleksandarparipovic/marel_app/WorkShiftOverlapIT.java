package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftOverlapException;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftCreateRequest;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A shift that runs into an existing one asks a question instead of failing.
 *
 * <p>{@code ex_work_shifts_no_overlap} is the guarantee and stays one. What it
 * cannot do is say WHICH shift is in the way or offer anything to do about it —
 * it arrived on screen as a raw SQL error naming a tstzrange. The overlap is now
 * found before the insert, and the two ways out come back with the concrete
 * intervals they would produce.
 *
 * <p>The case that prompted this: a third shift 22:00–06:00 against a first shift
 * recorded as starting at 05:00 rather than its defined 06:00 — one hour of
 * collision, and no way to tell that from the error.
 */
@Transactional
class WorkShiftOverlapIT extends AbstractIntegrationTest {

    @Autowired private WorkShiftService workShiftService;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    /** Employee, supervisor, a work category and the three shift definitions. */
    private record Setup(Long employeeId, Long supervisorId, Long categoryId,
                         Long firstShiftId, Long thirdShiftId) {}

    @SuppressWarnings("unchecked")
    private Setup setUp() {
        var scenario = fixture.scenario().build();
        Long supervisorId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM users ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
        // Shift definitions are production DATA, not schema — the test database
        // built from the migrations has none. Created here rather than seeded, so
        // this test states the times it depends on instead of inheriting them.
        entityManager.createNativeQuery("""
                INSERT INTO shifts (shift_code, name, start_time, end_time, is_active)
                SELECT v.code, v.name, v.st::time, v.et::time, TRUE
                FROM (VALUES ('S1','Prva smena','06:00','14:00'),
                             ('S2','Druga smena','14:00','22:00'),
                             ('S3','Treća smena','22:00','06:00')) AS v(code, name, st, et)
                WHERE NOT EXISTS (SELECT 1 FROM shifts x WHERE x.shift_code = v.code)""")
                .executeUpdate();
        entityManager.flush();

        List<Object[]> shifts = entityManager
                .createNativeQuery("SELECT id, start_time FROM shifts ORDER BY start_time")
                .getResultList();
        // 06:00 first, 14:00 second, 22:00 third.
        Long firstShiftId = ((Number) shifts.getFirst()[0]).longValue();
        Long thirdShiftId = ((Number) shifts.getLast()[0]).longValue();

        return new Setup(scenario.employee().getId(), supervisorId,
                scenario.workCategory().getId(), firstShiftId, thirdShiftId);
    }

    /**
     * The driver hands times back in the session zone, so 05:00+02 comes out as
     * 03:00Z. Comparing the printed string would be asserting the JVM's timezone,
     * not the shift's time.
     */
    private static void assertMoment(OffsetDateTime actual, String expected) {
        assertThat(actual.toInstant()).isEqualTo(OffsetDateTime.parse(expected).toInstant());
    }

    @SuppressWarnings("unchecked")
    private Long secondShiftId() {
        List<Object[]> shifts = entityManager
                .createNativeQuery("SELECT id, start_time FROM shifts ORDER BY start_time")
                .getResultList();
        return ((Number) shifts.get(1)[0]).longValue();
    }

    private WorkShiftCreateRequest request(Setup s, Long shiftId, String workDate, String resolution) {
        WorkShiftCreateRequest req = new WorkShiftCreateRequest();
        req.setEmployeeId(s.employeeId());
        req.setWorkDate(workDate);
        req.setShiftType(shiftId);
        req.setWorkCategoryCodeId(s.categoryId());
        req.setSupervisorId(s.supervisorId());
        req.setOverlapResolution(resolution);
        return req;
    }

    /** The first shift of the next day, pulled back to 05:00 the way the real one was. */
    private Long anEarlyFirstShift(Setup s) {
        var created = workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-02", null));
        entityManager.createNativeQuery(
                "UPDATE work_shifts SET start_at = TIMESTAMPTZ '2026-09-02 05:00+02' WHERE id = :id")
                .setParameter("id", created.id())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return created.id();
    }

    // ── the question ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a collision comes back as a question, with what is in the way")
    void aCollisionIsAQuestion() {
        Setup s = setUp();
        Long existing = anEarlyFirstShift(s);

        assertThatThrownBy(() ->
                workShiftService.createShift(request(s, s.thirdShiftId(), "2026-09-01", null)))
                .isInstanceOfSatisfying(WorkShiftOverlapException.class, ex -> {
                    assertThat(ex.getConflicts())
                            .singleElement()
                            .satisfies(c -> assertThat(c.id()).isEqualTo(existing));
                    assertThat(ex.getOptions())
                            .extracting(WorkShiftOverlapException.Option::resolution)
                            .containsExactlyInAnyOrder(
                                    WorkShiftOverlapException.Resolution.TRIM,
                                    WorkShiftOverlapException.Resolution.MERGE);
                });
    }

    @Test
    @DisplayName("the options carry the intervals they would actually produce")
    void theOptionsCarryTheirIntervals() {
        Setup s = setUp();
        anEarlyFirstShift(s);

        assertThatThrownBy(() ->
                workShiftService.createShift(request(s, s.thirdShiftId(), "2026-09-01", null)))
                .isInstanceOfSatisfying(WorkShiftOverlapException.class, ex -> {
                    var trim = ex.getOptions().stream()
                            .filter(o -> o.resolution() == WorkShiftOverlapException.Resolution.TRIM)
                            .findFirst().orElseThrow();
                    var merge = ex.getOptions().stream()
                            .filter(o -> o.resolution() == WorkShiftOverlapException.Resolution.MERGE)
                            .findFirst().orElseThrow();

                    // 22:00 → 05:00 instead of 22:00 → 06:00.
                    assertMoment(trim.endAt(), "2026-09-02T05:00+02:00");
                    // 22:00 → 14:00, the two as one.
                    assertMoment(merge.startAt(), "2026-09-01T22:00+02:00");
                    assertMoment(merge.endAt(), "2026-09-02T14:00+02:00");
                });
    }

    // ── the answers ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("TRIM creates the new shift, stopping where the existing one starts")
    void trimShortensTheNewShift() {
        Setup s = setUp();
        Long existing = anEarlyFirstShift(s);

        var created = workShiftService.createShift(
                request(s, s.thirdShiftId(), "2026-09-01", "TRIM"));

        assertThat(created.id()).isNotEqualTo(existing);
        var trimmed = workShiftRepository.findById(created.id()).orElseThrow();
        assertMoment(trimmed.getEndAt(), "2026-09-02T05:00+02:00");
        // The existing shift is not touched.
        assertMoment(workShiftRepository.findById(existing).orElseThrow().getStartAt(),
                "2026-09-02T05:00+02:00");
    }

    @Test
    @DisplayName("MERGE stretches the EXISTING shift and creates nothing")
    void mergeExtendsTheExistingShift() {
        Setup s = setUp();
        Long existing = anEarlyFirstShift(s);
        long before = workShiftRepository.count();

        var result = workShiftService.createShift(
                request(s, s.thirdShiftId(), "2026-09-01", "MERGE"));

        // The existing row, widened — NOT a replacement. It is the one that carries
        // the work logs, and a new row would leave them behind.
        assertThat(result.id()).isEqualTo(existing);
        assertThat(workShiftRepository.count()).isEqualTo(before);

        var merged = workShiftRepository.findById(existing).orElseThrow();
        assertMoment(merged.getStartAt(), "2026-09-01T22:00+02:00");
        assertMoment(merged.getEndAt(), "2026-09-02T14:00+02:00");
    }

    @Test
    @DisplayName("a collision at the START moves the start, it does not only offer a merge")
    void trimAlsoCutsFromTheFront() {
        Setup s = setUp();
        // The real shape: a first shift that ran long, 05:00 to 14:40.
        var first = workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-02", null));
        entityManager.createNativeQuery("""
                UPDATE work_shifts SET start_at = TIMESTAMPTZ '2026-09-02 05:00+02',
                                       end_at   = TIMESTAMPTZ '2026-09-02 14:40+02'
                WHERE id = :id""")
                .setParameter("id", first.id())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        Long secondShiftId = secondShiftId();

        // A second shift 14:00–22:00 runs into it over 14:00–14:40. The existing
        // shift does not START after it, so the first version of this offered a
        // merge and nothing else.
        assertThatThrownBy(() ->
                workShiftService.createShift(request(s, secondShiftId, "2026-09-02", null)))
                .isInstanceOfSatisfying(WorkShiftOverlapException.class, ex -> {
                    var trim = ex.getOptions().stream()
                            .filter(o -> o.resolution() == WorkShiftOverlapException.Resolution.TRIM)
                            .findFirst().orElseThrow();
                    assertMoment(trim.startAt(), "2026-09-02T14:40+02:00");
                    assertMoment(trim.endAt(), "2026-09-02T22:00+02:00");
                });

        var created = workShiftService.createShift(request(s, secondShiftId, "2026-09-02", "TRIM"));
        var saved = workShiftRepository.findById(created.id()).orElseThrow();
        // What was saved is exactly what the option advertised.
        assertMoment(saved.getStartAt(), "2026-09-02T14:40+02:00");
        assertMoment(saved.getEndAt(), "2026-09-02T22:00+02:00");
    }

    @Test
    @DisplayName("a shift swallowed whole by an existing one can only be merged")
    void aSwallowedShiftOffersOnlyMerge() {
        Setup s = setUp();
        var first = workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-02", null));
        entityManager.createNativeQuery("""
                UPDATE work_shifts SET start_at = TIMESTAMPTZ '2026-09-02 05:00+02',
                                       end_at   = TIMESTAMPTZ '2026-09-02 23:00+02'
                WHERE id = :id""")
                .setParameter("id", first.id())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // 14:00–22:00 sits entirely inside 05:00–23:00. Trimming either end leaves
        // nothing, so offering it would be offering an empty shift.
        assertThatThrownBy(() ->
                workShiftService.createShift(request(s, secondShiftId(), "2026-09-02", null)))
                .isInstanceOfSatisfying(WorkShiftOverlapException.class, ex ->
                        assertThat(ex.getOptions())
                                .extracting(WorkShiftOverlapException.Option::resolution)
                                .containsExactly(WorkShiftOverlapException.Resolution.MERGE));
    }

    // ── what is refused ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a shift that fits is created without any question")
    void noCollisionNoQuestion() {
        Setup s = setUp();
        // The first shift as DEFINED, 06:00. tstzrange is half-open, so a third
        // shift ending at 06:00 touches it without overlapping.
        workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-02", null));

        var created = workShiftService.createShift(
                request(s, s.thirdShiftId(), "2026-09-01", null));

        assertThat(created.id()).isNotNull();
    }

    /** A shift on each side of the new one, leaving a gap it would fill. */
    private void aShiftOnEachSide(Setup s) {
        var before = workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-01", null));
        entityManager.createNativeQuery("""
                UPDATE work_shifts SET start_at = TIMESTAMPTZ '2026-09-01 18:00+02',
                                       end_at   = TIMESTAMPTZ '2026-09-01 23:00+02'
                WHERE id = :id""")
                .setParameter("id", before.id()).executeUpdate();

        var after = workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-02", null));
        entityManager.createNativeQuery("""
                UPDATE work_shifts SET start_at = TIMESTAMPTZ '2026-09-02 04:00+02',
                                       end_at   = TIMESTAMPTZ '2026-09-02 12:00+02'
                WHERE id = :id""")
                .setParameter("id", after.id()).executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("caught between two shifts, all three ways out are offered")
    void betweenTwoShiftsOffersThree() {
        Setup s = setUp();
        aShiftOnEachSide(s);   // 18:00–23:00 before, 04:00–12:00 after

        assertThatThrownBy(() ->
                workShiftService.createShift(request(s, s.thirdShiftId(), "2026-09-01", null)))
                .isInstanceOfSatisfying(WorkShiftOverlapException.class, ex -> {
                    assertThat(ex.getConflicts()).hasSize(2);
                    assertThat(ex.getOptions())
                            .extracting(WorkShiftOverlapException.Option::resolution)
                            .containsExactlyInAnyOrder(
                                    WorkShiftOverlapException.Resolution.MERGE_PREVIOUS,
                                    WorkShiftOverlapException.Resolution.MERGE_NEXT,
                                    WorkShiftOverlapException.Resolution.FIT_BETWEEN);

                    var fit = ex.getOptions().stream()
                            .filter(o -> o.resolution() == WorkShiftOverlapException.Resolution.FIT_BETWEEN)
                            .findFirst().orElseThrow();
                    assertMoment(fit.startAt(), "2026-09-01T23:00+02:00");
                    assertMoment(fit.endAt(), "2026-09-02T04:00+02:00");
                });
    }

    @Test
    @DisplayName("merging with the previous stops where the next one begins")
    void mergePreviousStopsAtTheNext() {
        Setup s = setUp();
        aShiftOnEachSide(s);
        long before = workShiftRepository.count();

        var result = workShiftService.createShift(
                request(s, s.thirdShiftId(), "2026-09-01", "MERGE_PREVIOUS"));

        assertThat(workShiftRepository.count()).isEqualTo(before);
        var merged = workShiftRepository.findById(result.id()).orElseThrow();
        assertMoment(merged.getStartAt(), "2026-09-01T18:00+02:00");
        // Not 06:00 — that would run straight into the 04:00 shift and trade one
        // collision for another.
        assertMoment(merged.getEndAt(), "2026-09-02T04:00+02:00");
    }

    @Test
    @DisplayName("merging with the next starts where the previous one ends")
    void mergeNextStartsAtThePrevious() {
        Setup s = setUp();
        aShiftOnEachSide(s);

        var result = workShiftService.createShift(
                request(s, s.thirdShiftId(), "2026-09-01", "MERGE_NEXT"));

        var merged = workShiftRepository.findById(result.id()).orElseThrow();
        assertMoment(merged.getStartAt(), "2026-09-01T23:00+02:00");
        assertMoment(merged.getEndAt(), "2026-09-02T12:00+02:00");
    }

    @Test
    @DisplayName("filling the gap creates a shift between them, touching neither")
    void fitBetweenFillsTheGap() {
        Setup s = setUp();
        aShiftOnEachSide(s);
        long before = workShiftRepository.count();

        var created = workShiftService.createShift(
                request(s, s.thirdShiftId(), "2026-09-01", "FIT_BETWEEN"));

        assertThat(workShiftRepository.count()).isEqualTo(before + 1);
        var fitted = workShiftRepository.findById(created.id()).orElseThrow();
        assertMoment(fitted.getStartAt(), "2026-09-01T23:00+02:00");
        assertMoment(fitted.getEndAt(), "2026-09-02T04:00+02:00");
    }

    @Test
    @DisplayName("two shifts that both begin inside the new one get no options")
    void twoConflictsWithNoPreviousGetNothing() {
        Setup s = setUp();
        // Both begin after the new shift does, so there is no "previous" and no
        // "next" — two shifts inside it, and no honest way to choose between them.
        var a = workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-01", null));
        entityManager.createNativeQuery("""
                UPDATE work_shifts SET start_at = TIMESTAMPTZ '2026-09-01 23:00+02',
                                       end_at   = TIMESTAMPTZ '2026-09-02 00:00+02'
                WHERE id = :id""").setParameter("id", a.id()).executeUpdate();
        var b = workShiftService.createShift(request(s, s.firstShiftId(), "2026-09-02", null));
        entityManager.createNativeQuery("""
                UPDATE work_shifts SET start_at = TIMESTAMPTZ '2026-09-02 02:00+02',
                                       end_at   = TIMESTAMPTZ '2026-09-02 03:00+02'
                WHERE id = :id""").setParameter("id", b.id()).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() ->
                workShiftService.createShift(request(s, s.thirdShiftId(), "2026-09-01", null)))
                .isInstanceOfSatisfying(WorkShiftOverlapException.class, ex -> {
                    assertThat(ex.getConflicts()).hasSize(2);
                    assertThat(ex.getOptions()).isEmpty();
                });
    }

    @Test
    @DisplayName("the exclusion constraint is still the guarantee underneath")
    void theConstraintStillGuards() {
        Setup s = setUp();
        Long existing = anEarlyFirstShift(s);
        OffsetDateTime start = workShiftRepository.findById(existing).orElseThrow().getStartAt();

        // Going round the service — the database still refuses.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO work_shifts (employee_id, shift_id, supervisor_id, start_at, end_at,
                                             work_date, is_active)
                    VALUES (:emp, :shift, :sup, :start, :end, DATE '2026-09-02', TRUE)""")
                    .setParameter("emp", s.employeeId())
                    .setParameter("shift", s.thirdShiftId())
                    .setParameter("sup", s.supervisorId())
                    .setParameter("start", start.plusMinutes(30))
                    .setParameter("end", start.plusHours(2))
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("ex_work_shifts_no_overlap");
    }
}
