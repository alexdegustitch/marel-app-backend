package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.shift.ShiftBreakResolver;
import com.aleksandarparipovic.marel_app.utils.dates.DateUtil;
import com.aleksandarparipovic.marel_app.utils.dto.StartEndResult;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * The one log a shift's PAUZA is drawn with.
 *
 * <p>The counterpart of {@code AbsenceLogWriter}, one window smaller: where an
 * absence spans the whole shift, the pauza spans the window {@code
 * ShiftBreakResolver} answers with — the employee's own arrangement when they
 * have one, the shift's versioned default otherwise, and nothing at all when
 * neither is configured.
 *
 * <p><b>Which operation.</b> The standalone operation named "Pauza" (V65 made
 * operations without a product possible, and the partial unique index makes
 * the name unambiguous among them). It is administered in the UI, not seeded
 * by a migration, so missing configuration is reported out loud rather than
 * silently drawing nothing.
 *
 * <p><b>Which category.</b> The operation's own — the SOURCE category, exactly
 * as a hand-picked karton row would get. The category pairs / mappings the
 * recalculation already applies derive the effective one from the shift's
 * context; nothing here decides pay.
 *
 * <p><b>When it is written.</b> Only when somebody asks — the checkbox on
 * shift creation, or the karton's own toggle (which goes through the ordinary
 * draft save instead of this writer). Idempotent per shift: a shift already
 * carrying a live Pauza log gets the same one back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BreakLogWriter {

    /** The standalone operation the karton draws a break with. */
    public static final String BREAK_OPERATION_NAME = "Pauza";

    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ShiftBreakResolver breakResolver;
    private final OperationRepository operationRepository;
    private final WorkLogRepository workLogRepository;
    private final DateUtil dateUtil;

    /**
     * Ensures the shift carries its pauza log, and hands it back — or empty
     * when the break window does not fit the shift's ACTUAL interval (a shift
     * trimmed around a collision may no longer contain it; a break the shift
     * cannot hold is not drawn, and the karton shows exactly that).
     *
     * @throws IllegalStateException when no break window is configured for the
     *         shift, or the standalone "Pauza" operation does not exist — both
     *         are configuration gaps a person must fix, said plainly.
     */
    public Optional<WorkLog> ensureBreakLog(WorkShift shift) {
        ShiftBreakResolver.ResolvedBreak window = breakResolver
                .resolve(shift.getEmployee().getId(), shift.getShift().getId(), shift.getWorkDate())
                .orElseThrow(() -> new IllegalStateException(
                        "Smena nema podešenu pauzu — unesite je u šifarniku Smene ili kod radnika."));

        Operation operation = breakOperation();

        Optional<WorkLog> existing = findBreakLog(shift, operation);
        if (existing.isPresent()) {
            return existing;
        }

        // Anchored to the shift's window, not the calendar date — shift III's
        // 02:00 belongs to the morning after the work date.
        StartEndResult time = dateUtil.buildStartEnd(
                shift.getWorkDate(),
                window.startTime().format(HH_MM),
                window.endTime().format(HH_MM),
                shift.getStartAt(), shift.getEndAt(), ZONE);

        if (time.start().isBefore(shift.getStartAt()) || time.end().isAfter(shift.getEndAt())) {
            log.warn("Break {}–{} does not fit shift {} ({}–{}); not drawn",
                    window.startTime(), window.endTime(), shift.getId(), shift.getStartAt(), shift.getEndAt());
            return Optional.empty();
        }

        WorkLog saved = workLogRepository.save(WorkLog.builder()
                .workShift(shift)
                .operation(operation)
                .workCode(operation.getWorkCodeCategory())
                .startAt(time.start())
                .endAt(time.end())
                .quantity(0)
                .scrap(0)
                .isActive(true)
                .normMultiplierSnapshot(BigDecimal.valueOf(
                        operation.getWorkCodeCategory().getNormMultiplier() == null
                                ? 0d : operation.getWorkCodeCategory().getNormMultiplier()))
                .build());
        log.debug("Pauza log {} written on shift {} ({}–{})",
                saved.getId(), shift.getId(), time.start(), time.end());
        return Optional.of(saved);
    }

    /** The live Pauza log on this shift, if one is drawn. */
    public Optional<WorkLog> findBreakLog(WorkShift shift, Operation operation) {
        return workLogRepository.findActiveLogsWithRefsForShift(shift.getId()).stream()
                .filter(wl -> wl.getOperation() != null
                        && operation.getId().equals(wl.getOperation().getId()))
                .findFirst();
    }

    /**
     * Missing configuration is an error, not a shrug — the same stance
     * {@code AbsenceLogWriter} takes about its operations.
     */
    public Operation breakOperation() {
        return operationRepository.findStandaloneByName(BREAK_OPERATION_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Ne postoji osnovna operacija „" + BREAK_OPERATION_NAME
                                + "“. Kreirajte je na stranici Operacije pre upisa pauze."));
    }
}
