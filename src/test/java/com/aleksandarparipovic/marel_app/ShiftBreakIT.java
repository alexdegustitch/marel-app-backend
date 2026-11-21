package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.shift.EmployeeShiftBreakService;
import com.aleksandarparipovic.marel_app.shift.ShiftBreakResolver;
import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftBreakRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_shift.BreakLogWriter;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftCreateRequest;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shift's pauze: several per shift, resolved through dated periods PER
 * BREAK (default → employee's own → suppressed = none), drawn as one log per
 * window on the standalone "Pauza" operation, and only when asked for.
 */
@Transactional
class ShiftBreakIT extends AbstractIntegrationTest {

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EmployeeShiftBreakService breakService;
    @Autowired private ShiftBreakResolver breakResolver;
    @Autowired private BreakLogWriter breakLogWriter;
    @Autowired private OperationService operationService;
    @Autowired private WorkShiftService workShiftService;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private UserRepository userRepository;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 16);
    private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

    private Long createPauzaOperation(PayrollScenarioFixture.Scenario scenario) {
        OperationCreateRequest request = new OperationCreateRequest();
        request.setStandalone(true);
        request.setOperationName(BreakLogWriter.BREAK_OPERATION_NAME);
        request.setWorkCodeCategoryId(scenario.workCategory().getId());
        return operationService.create(request).getOperationId();
    }

    private ChangeShiftBreakRequest window(Long shiftId, int breakNo,
                                           LocalTime start, LocalTime end, LocalDate from) {
        ChangeShiftBreakRequest request = new ChangeShiftBreakRequest();
        request.setShiftId(shiftId);
        request.setBreakNo(breakNo);
        request.setStartTime(start);
        request.setEndTime(end);
        request.setValidFrom(from);
        return request;
    }

    private ChangeShiftBreakRequest suppression(Long shiftId, int breakNo, LocalDate from) {
        ChangeShiftBreakRequest request = new ChangeShiftBreakRequest();
        request.setShiftId(shiftId);
        request.setBreakNo(breakNo);
        request.setSuppressed(true);
        request.setValidFrom(from);
        return request;
    }

    @Test
    @DisplayName("per break: the default answers until the employee's own takes over")
    void resolutionOrder() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        var shift = fixture.workShift(scenario.employee(), DAY, 6, 480);
        Long shiftTypeId = shift.getShift().getId();
        Long employeeId = scenario.employee().getId();

        // Nothing configured → no break, not a guessed one.
        assertThat(breakResolver.resolveAll(employeeId, shiftTypeId, DAY)).isEmpty();

        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 1, LocalTime.of(11, 0), LocalTime.of(11, 30), EPOCH));
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 2, LocalTime.of(13, 0), LocalTime.of(13, 15), EPOCH));

        List<ShiftBreakResolver.ResolvedBreak> defaults =
                breakResolver.resolveAll(employeeId, shiftTypeId, DAY);
        assertThat(defaults).hasSize(2);
        assertThat(defaults.get(0).breakNo()).isEqualTo(1);
        assertThat(defaults.get(0).startTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(defaults.get(0).employeeOwn()).isFalse();
        assertThat(defaults.get(1).breakNo()).isEqualTo(2);

        // The employee's own window wins WITHIN its break, from its date only.
        breakService.change(employeeId,
                window(shiftTypeId, 2, LocalTime.of(14, 0), LocalTime.of(14, 15), DAY));
        List<ShiftBreakResolver.ResolvedBreak> mixed =
                breakResolver.resolveAll(employeeId, shiftTypeId, DAY);
        assertThat(mixed).hasSize(2);
        assertThat(mixed.get(0).startTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(mixed.get(0).employeeOwn()).isFalse();
        assertThat(mixed.get(1).startTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(mixed.get(1).employeeOwn()).isTrue();
        assertThat(breakResolver.resolveAll(employeeId, shiftTypeId, DAY.minusDays(1)))
                .allSatisfy(b -> assertThat(b.employeeOwn()).isFalse());
    }

    @Test
    @DisplayName("an employee can skip a default break and carry an extra one of their own")
    void suppressionAndExtras() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        var shift = fixture.workShift(scenario.employee(), DAY, 6, 480);
        Long shiftTypeId = shift.getShift().getId();
        Long employeeId = scenario.employee().getId();

        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 1, LocalTime.of(11, 0), LocalTime.of(11, 30), EPOCH));

        // Skip the default break 1; add an own break 2 the shift never had.
        breakService.change(employeeId, suppression(shiftTypeId, 1, DAY));
        breakService.change(employeeId,
                window(shiftTypeId, 2, LocalTime.of(9, 0), LocalTime.of(9, 15), DAY));

        List<ShiftBreakResolver.ResolvedBreak> resolved =
                breakResolver.resolveAll(employeeId, shiftTypeId, DAY);
        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().breakNo()).isEqualTo(2);
        assertThat(resolved.getFirst().employeeOwn()).isTrue();

        // The day before, the default break 1 still answers alone.
        List<ShiftBreakResolver.ResolvedBreak> before =
                breakResolver.resolveAll(employeeId, shiftTypeId, DAY.minusDays(1));
        assertThat(before).hasSize(1);
        assertThat(before.getFirst().breakNo()).isEqualTo(1);

        // Close the suppression — the default is back from the next day.
        breakService.close(employeeId, shiftTypeId, 1, DAY);
        assertThat(breakResolver.resolveAll(employeeId, shiftTypeId, DAY.plusDays(1)))
                .extracting(ShiftBreakResolver.ResolvedBreak::breakNo)
                .containsExactly(1, 2);

        // A suppressed spell must not carry times; a default is never suppressed.
        ChangeShiftBreakRequest bad = suppression(shiftTypeId, 1, DAY.plusDays(2));
        bad.setStartTime(LocalTime.of(11, 0));
        bad.setEndTime(LocalTime.of(11, 30));
        assertThatThrownBy(() -> breakService.change(employeeId, bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> breakService.changeDefault(shiftTypeId,
                suppression(shiftTypeId, 1, DAY.plusDays(2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the writer draws one Pauza log per window — idempotently, anchored across midnight")
    void writerDrawsTheBreaks() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        createPauzaOperation(scenario);

        // An overnight shift (22:00Z–06:00Z = 00:00–08:00 Belgrade); both
        // breaks' clock times belong to the MORNING AFTER the work date.
        var shift = fixture.workShift(scenario.employee(), DAY, 22, 480);
        Long shiftTypeId = shift.getShift().getId();
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 1, LocalTime.of(2, 0), LocalTime.of(2, 30), EPOCH));
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 2, LocalTime.of(5, 0), LocalTime.of(5, 15), EPOCH));

        List<WorkLog> written = breakLogWriter.ensureBreakLogs(shift);
        assertThat(written).hasSize(2);

        var zone = java.time.ZoneId.of("Europe/Belgrade");
        WorkLog night = written.get(0);
        assertThat(night.getStartAt().toInstant())
                .isEqualTo(DAY.plusDays(1).atTime(2, 0).atZone(zone).toInstant());
        assertThat(night.getEndAt().toInstant())
                .isEqualTo(DAY.plusDays(1).atTime(2, 30).atZone(zone).toInstant());
        WorkLog morning = written.get(1);
        assertThat(morning.getStartAt().toInstant())
                .isEqualTo(DAY.plusDays(1).atTime(5, 0).atZone(zone).toInstant());
        for (WorkLog log : written) {
            assertThat(log.getStartAt()).isAfter(shift.getStartAt());
            assertThat(log.getEndAt()).isBefore(shift.getEndAt());
            assertThat(log.getQuantity()).isZero();
            assertThat(log.getProductionOrder()).isNull();
            assertThat(log.getOperation().isStandalone()).isTrue();
            // The SOURCE category is the operation's own; pairs/mappings derive
            // the effective one in recalc, never here.
            assertThat(log.getWorkCode().getId()).isEqualTo(scenario.workCategory().getId());
        }

        // Asked twice, drawn once.
        assertThat(breakLogWriter.ensureBreakLogs(shift)).isEmpty();
        assertThat(breakLogWriter.findBreakLogs(shift, breakLogWriter.breakOperation())).hasSize(2);
    }

    @Test
    @DisplayName("missing configuration is said out loud, not skipped")
    void missingConfigurationIsLoud() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        var shift = fixture.workShift(scenario.employee(), DAY, 6, 480);

        // No break window configured for the shift type.
        assertThatThrownBy(() -> breakLogWriter.ensureBreakLogs(shift))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nema podešenu pauzu");

        // Window configured, but the standalone "Pauza" operation is missing.
        Long shiftTypeId = shift.getShift().getId();
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 1, LocalTime.of(11, 0), LocalTime.of(11, 30), EPOCH));
        assertThatThrownBy(() -> breakLogWriter.ensureBreakLogs(shift))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pauza");
    }

    @Test
    @DisplayName("creating a shift with the checkbox draws every break right away")
    void createShiftWithBreak() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        createPauzaOperation(scenario);

        // The shift TYPE comes from a throwaway shift on another day; the
        // created one is the row under test.
        var template = fixture.workShift(scenario.employee(), DAY.minusDays(7), 6, 480);
        Long shiftTypeId = template.getShift().getId();
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 1, LocalTime.of(11, 0), LocalTime.of(11, 30), EPOCH));
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, 2, LocalTime.of(13, 0), LocalTime.of(13, 15), EPOCH));

        WorkShiftCreateRequest request = new WorkShiftCreateRequest();
        request.setEmployeeId(scenario.employee().getId());
        request.setWorkDate(DAY.toString());
        request.setShiftType(shiftTypeId);
        request.setWorkCategoryCodeId(scenario.workCategory().getId());
        request.setSupervisorId(userRepository.findAll().getFirst().getId());
        request.setIncludeBreak(true);

        Long createdId = workShiftService.createShift(request).id();

        var created = workShiftRepository.findById(createdId).orElseThrow();
        List<WorkLog> pauze = breakLogWriter.findBreakLogs(created, breakLogWriter.breakOperation());
        assertThat(pauze).hasSize(2);
        assertThat(pauze.getFirst().getStartAt().toInstant())
                .isEqualTo(DAY.atTime(11, 0).atZone(java.time.ZoneId.of("Europe/Belgrade")).toInstant());
        for (WorkLog pauza : pauze) {
            assertThat(pauza.getStartAt()).isAfter(created.getStartAt());
            assertThat(pauza.getEndAt()).isBefore(created.getEndAt());
        }
    }
}
