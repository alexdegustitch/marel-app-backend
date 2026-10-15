package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.OperationService;
import com.aleksandarparipovic.marel_app.operation.dto.OperationCreateRequest;
import com.aleksandarparipovic.marel_app.shift.EmployeeShiftBreakService;
import com.aleksandarparipovic.marel_app.shift.ShiftBreakResolver;
import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftTimeRequest;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shift's pauza: resolved through dated periods (default → employee's
 * own), drawn as ONE log on the standalone "Pauza" operation, and only when
 * asked for.
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

    private Long createPauzaOperation(PayrollScenarioFixture.Scenario scenario) {
        OperationCreateRequest request = new OperationCreateRequest();
        request.setStandalone(true);
        request.setOperationName(BreakLogWriter.BREAK_OPERATION_NAME);
        request.setWorkCodeCategoryId(scenario.workCategory().getId());
        return operationService.create(request).getOperationId();
    }

    private ChangeShiftTimeRequest window(Long shiftId, LocalTime start, LocalTime end, LocalDate from) {
        ChangeShiftTimeRequest request = new ChangeShiftTimeRequest();
        request.setShiftId(shiftId);
        request.setStartTime(start);
        request.setEndTime(end);
        request.setValidFrom(from);
        return request;
    }

    @Test
    @DisplayName("the default window answers until the employee's own takes over")
    void resolutionOrder() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        var shift = fixture.workShift(scenario.employee(), DAY, 6, 480);
        Long shiftTypeId = shift.getShift().getId();
        Long employeeId = scenario.employee().getId();

        // Nothing configured → no break, not a guessed one.
        assertThat(breakResolver.resolve(employeeId, shiftTypeId, DAY)).isEmpty();

        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, LocalTime.of(11, 0), LocalTime.of(11, 30), LocalDate.of(1970, 1, 1)));
        assertThat(breakResolver.resolve(employeeId, shiftTypeId, DAY))
                .hasValueSatisfying(resolved -> {
                    assertThat(resolved.startTime()).isEqualTo(LocalTime.of(11, 0));
                    assertThat(resolved.employeeOwn()).isFalse();
                });

        // The employee's own window wins from its date, and only from it.
        breakService.change(employeeId,
                window(shiftTypeId, LocalTime.of(10, 0), LocalTime.of(10, 30), DAY));
        assertThat(breakResolver.resolve(employeeId, shiftTypeId, DAY))
                .hasValueSatisfying(resolved -> {
                    assertThat(resolved.startTime()).isEqualTo(LocalTime.of(10, 0));
                    assertThat(resolved.employeeOwn()).isTrue();
                });
        assertThat(breakResolver.resolve(employeeId, shiftTypeId, DAY.minusDays(1)))
                .hasValueSatisfying(resolved -> assertThat(resolved.employeeOwn()).isFalse());
    }

    @Test
    @DisplayName("the writer draws one Pauza log in the window — idempotently, anchored across midnight")
    void writerDrawsTheBreak() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        createPauzaOperation(scenario);

        // An overnight shift, 22:00–06:00; the break at 02:00 belongs to the
        // MORNING AFTER the work date.
        var shift = fixture.workShift(scenario.employee(), DAY, 22, 480);
        Long shiftTypeId = shift.getShift().getId();
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, LocalTime.of(2, 0), LocalTime.of(2, 30), LocalDate.of(1970, 1, 1)));

        Optional<WorkLog> written = breakLogWriter.ensureBreakLog(shift);
        assertThat(written).isPresent();
        WorkLog log = written.get();
        // 02:00 Belgrade time on the day AFTER the work date — inside the
        // shift's window, not on the calendar date the shift started.
        var zone = java.time.ZoneId.of("Europe/Belgrade");
        assertThat(log.getStartAt().toInstant())
                .isEqualTo(DAY.plusDays(1).atTime(2, 0).atZone(zone).toInstant());
        assertThat(log.getEndAt().toInstant())
                .isEqualTo(DAY.plusDays(1).atTime(2, 30).atZone(zone).toInstant());
        assertThat(log.getStartAt()).isAfter(shift.getStartAt());
        assertThat(log.getEndAt()).isBefore(shift.getEndAt());
        assertThat(log.getQuantity()).isZero();
        assertThat(log.getProductionOrder()).isNull();
        assertThat(log.getOperation().isStandalone()).isTrue();
        // The SOURCE category is the operation's own; pairs/mappings derive the
        // effective one in recalc, never here.
        assertThat(log.getWorkCode().getId()).isEqualTo(scenario.workCategory().getId());

        // Asked twice, drawn once.
        Optional<WorkLog> again = breakLogWriter.ensureBreakLog(shift);
        assertThat(again).isPresent();
        assertThat(again.get().getId()).isEqualTo(log.getId());
    }

    @Test
    @DisplayName("missing configuration is said out loud, not skipped")
    void missingConfigurationIsLoud() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        var shift = fixture.workShift(scenario.employee(), DAY, 6, 480);

        // No break window configured for the shift type.
        assertThatThrownBy(() -> breakLogWriter.ensureBreakLog(shift))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nema podešenu pauzu");

        // Window configured, but the standalone "Pauza" operation is missing.
        Long shiftTypeId = shift.getShift().getId();
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, LocalTime.of(11, 0), LocalTime.of(11, 30), LocalDate.of(1970, 1, 1)));
        assertThatThrownBy(() -> breakLogWriter.ensureBreakLog(shift))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pauza");
    }

    @Test
    @DisplayName("creating a shift with the checkbox draws the break right away")
    void createShiftWithBreak() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        createPauzaOperation(scenario);

        // The shift TYPE comes from a throwaway shift on another day; the
        // created one is the row under test.
        var template = fixture.workShift(scenario.employee(), DAY.minusDays(7), 6, 480);
        Long shiftTypeId = template.getShift().getId();
        breakService.changeDefault(shiftTypeId,
                window(shiftTypeId, LocalTime.of(11, 0), LocalTime.of(11, 30), LocalDate.of(1970, 1, 1)));

        WorkShiftCreateRequest request = new WorkShiftCreateRequest();
        request.setEmployeeId(scenario.employee().getId());
        request.setWorkDate(DAY.toString());
        request.setShiftType(shiftTypeId);
        request.setWorkCategoryCodeId(scenario.workCategory().getId());
        request.setSupervisorId(userRepository.findAll().getFirst().getId());
        request.setIncludeBreak(true);

        Long createdId = workShiftService.createShift(request).id();

        var created = workShiftRepository.findById(createdId).orElseThrow();
        var pauza = breakLogWriter.findBreakLog(created, breakLogWriter.breakOperation());
        assertThat(pauza).isPresent();
        assertThat(pauza.get().getStartAt().toInstant())
                .isEqualTo(DAY.atTime(11, 0).atZone(java.time.ZoneId.of("Europe/Belgrade")).toInstant());
        assertThat(pauza.get().getStartAt()).isAfter(created.getStartAt());
        assertThat(pauza.get().getEndAt()).isBefore(created.getEndAt());
    }
}
