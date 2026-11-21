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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The logs a shift's PAUZE are drawn with — one per break window.
 *
 * <p>The counterpart of {@code AbsenceLogWriter}, one window smaller: where an
 * absence spans the whole shift, each pauza spans one window {@code
 * ShiftBreakResolver} answers with — the employee's own arrangements where
 * they have them, the shift's versioned defaults otherwise, and nothing at all
 * when neither is configured.
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
 * draft save instead of this writer). Idempotent per window: a break whose
 * interval an existing live Pauza log already overlaps is left to that log —
 * hand-moved rows included — so asking twice draws nothing twice.
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
     * Ensures the shift carries a log for each of its pauze, and hands back
     * the NEWLY written ones. A window that does not fit the shift's ACTUAL
     * interval is skipped (a shift trimmed around a collision may no longer
     * contain it; a break the shift cannot hold is not drawn, and the karton
     * shows exactly that), as is a window an existing live Pauza log already
     * covers.
     *
     * @throws IllegalStateException when no break window is configured for the
     *         shift at all, or the standalone "Pauza" operation does not exist
     *         — both are configuration gaps a person must fix, said plainly.
     */
    public List<WorkLog> ensureBreakLogs(WorkShift shift) {
        List<ShiftBreakResolver.ResolvedBreak> windows = breakResolver.resolveAll(
                shift.getEmployee().getId(), shift.getShift().getId(), shift.getWorkDate());
        if (windows.isEmpty()) {
            throw new IllegalStateException(
                    "Smena nema podešenu pauzu — unesite je u šifarniku Smene ili kod radnika.");
        }

        Operation operation = breakOperation();
        List<WorkLog> existing = findBreakLogs(shift, operation);
        List<WorkLog> written = new ArrayList<>();

        for (ShiftBreakResolver.ResolvedBreak window : windows) {
            // Anchored to the shift's window, not the calendar date — shift
            // III's 02:00 belongs to the morning after the work date.
            StartEndResult time = dateUtil.buildStartEnd(
                    shift.getWorkDate(),
                    window.startTime().format(HH_MM),
                    window.endTime().format(HH_MM),
                    shift.getStartAt(), shift.getEndAt(), ZONE);

            if (time.start().isBefore(shift.getStartAt()) || time.end().isAfter(shift.getEndAt())) {
                log.warn("Break {} ({}–{}) does not fit shift {} ({}–{}); not drawn",
                        window.breakNo(), window.startTime(), window.endTime(),
                        shift.getId(), shift.getStartAt(), shift.getEndAt());
                continue;
            }

            // A live Pauza log overlapping the window — the same one written
            // earlier, or one somebody moved by hand — already draws it.
            boolean covered = existing.stream().anyMatch(wl ->
                    wl.getStartAt().isBefore(time.end()) && wl.getEndAt().isAfter(time.start()));
            if (covered) {
                continue;
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
            existing.add(saved);
            written.add(saved);
            log.debug("Pauza {} log {} written on shift {} ({}–{})",
                    window.breakNo(), saved.getId(), shift.getId(), time.start(), time.end());
        }
        return written;
    }

    /** The live Pauza logs on this shift, earliest first. */
    public List<WorkLog> findBreakLogs(WorkShift shift, Operation operation) {
        return workLogRepository.findActiveLogsWithRefsForShift(shift.getId()).stream()
                .filter(wl -> wl.getOperation() != null
                        && operation.getId().equals(wl.getOperation().getId()))
                .sorted(java.util.Comparator.comparing(WorkLog::getStartAt))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** The first live Pauza log on this shift, if one is drawn. */
    public Optional<WorkLog> findBreakLog(WorkShift shift, Operation operation) {
        return findBreakLogs(shift, operation).stream().findFirst();
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
