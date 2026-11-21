package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftBreakRequest;
import com.aleksandarparipovic.marel_app.shift.dto.EmployeeShiftBreakPeriodDto;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A shift's pauza windows, kept as dated spells — the employee's own and the
 * shift's defaults alike, SEVERAL PER SHIFT, each identified by its breakNo.
 * The exact sibling of {@link EmployeeShiftTimeService} / the default handling
 * in {@code ShiftAdminService}, with the same transition semantics: the
 * previous spell is CLOSED the day before the new one begins, never edited to
 * say something else; the same start date is a CORRECTION of the same spell.
 *
 * <p>An employee spell may also be SUPPRESSED — this person does not take that
 * default break — which is a dated statement like any other, not a deletion.
 *
 * <p>NOTHING HERE REWRITES A KARTON ROW ALREADY WRITTEN — a moved window
 * reaches only breaks drawn after the move.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeShiftBreakService {

    private final EmployeeShiftBreakPeriodRepository repository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final CurrentUserService currentUserService;

    /** Every spell of this employee's own break windows, newest first. */
    @Transactional(readOnly = true)
    public List<EmployeeShiftBreakPeriodDto> history(Long employeeId) {
        return repository.findHistoryFor(employeeId).stream()
                .map(EmployeeShiftBreakPeriodDto::from)
                .toList();
    }

    /** State one break for the employee — their own window, or that they skip it. */
    @Transactional
    public EmployeeShiftBreakPeriodDto change(Long employeeId, ChangeShiftBreakRequest request) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Zaposleni ne postoji: " + employeeId));
        Shift shift = shiftRepository.findById(request.getShiftId())
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + request.getShiftId()));

        validate(request);

        EmployeeShiftBreakPeriod open = repository
                .findOpenFor(employeeId, shift.getId(), request.getBreakNo()).orElse(null);

        if (open != null && open.getValidFrom().equals(request.getValidFrom())) {
            // The same start date is a CORRECTION — no second version opens.
            apply(open, request);
            return EmployeeShiftBreakPeriodDto.from(repository.saveAndFlush(open));
        }

        if (open != null) {
            if (!request.getValidFrom().isAfter(open.getValidFrom())) {
                throw new IllegalArgumentException(
                        "Nova pauza mora da počne posle početka trenutne ("
                                + open.getValidFrom() + ").");
            }
            open.setValidTo(request.getValidFrom().minusDays(1));
            repository.saveAndFlush(open);
        }

        List<EmployeeShiftBreakPeriod> clashing = repository.findOwnOverlapping(
                employeeId, shift.getId(), request.getBreakNo(),
                request.getValidFrom(), request.getValidTo(),
                open != null ? open.getId() : -1L);
        if (!clashing.isEmpty()) {
            throw new ConflictException(
                    "Za " + breakLabel(request.getBreakNo()) + " smene " + shift.getShiftCode()
                            + " već postoji period koji se preklapa sa unetim ("
                            + clashing.getFirst().getValidFrom()
                            + (clashing.getFirst().getValidTo() != null ? " – " + clashing.getFirst().getValidTo() : " – ")
                            + ").");
        }

        EmployeeShiftBreakPeriod created = repository.saveAndFlush(EmployeeShiftBreakPeriod.builder()
                .employee(employee)
                .shift(shift)
                .breakNo(request.getBreakNo())
                .suppressed(request.isSuppressed())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(request.getNote())
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.debug("Employee {} break {} on shift {}: {} from {}",
                employeeId, request.getBreakNo(), shift.getShiftCode(),
                request.isSuppressed() ? "suppressed"
                        : request.getStartTime() + "–" + request.getEndTime(),
                request.getValidFrom());

        return EmployeeShiftBreakPeriodDto.from(created);
    }

    /** Close the open spell of one break — the default applies again from the next day. */
    @Transactional
    public void close(Long employeeId, Long shiftId, Integer breakNo, LocalDate endedOn) {
        EmployeeShiftBreakPeriod open = repository.findOpenFor(employeeId, shiftId, breakNo)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Zaposleni nema otvorenu sopstvenu pauzu br. " + breakNo + " za tu smenu."));
        if (endedOn.isBefore(open.getValidFrom())) {
            throw new IllegalArgumentException("Kraj ne može biti pre početka (" + open.getValidFrom() + ").");
        }
        open.setValidTo(endedOn);
        repository.saveAndFlush(open);
    }

    /** Withdraw a spell that should never have existed. Archived, not deleted. */
    @Transactional
    public void archive(Long employeeId, Long periodId) {
        EmployeeShiftBreakPeriod period = repository.findById(periodId)
                .orElseThrow(() -> new EntityNotFoundException("Period ne postoji: " + periodId));
        if (period.getEmployee() == null || !period.getEmployee().getId().equals(employeeId)) {
            throw new EntityNotFoundException("Period ne pripada ovom zaposlenom.");
        }
        if (period.getArchivedAt() != null) {
            return;
        }
        period.setArchivedAt(OffsetDateTime.now());
        period.setArchivedBy(currentUserService.getCurrentUserId());
        repository.saveAndFlush(period);
    }

    // ── The shift's DEFAULT breaks — the šifarnik's side ─────────────────────

    /** The default-break history of one shift, newest first. */
    @Transactional(readOnly = true)
    public List<EmployeeShiftBreakPeriodDto> defaultHistory(Long shiftId) {
        return repository.findDefaultHistoryFor(shiftId).stream()
                .map(EmployeeShiftBreakPeriodDto::from)
                .toList();
    }

    /**
     * Move one of the shift's default breaks from a date — or state it for the
     * first time. Same correction/versioning rules as the employee spell. A
     * default is never suppressed; it is closed instead.
     */
    @Transactional
    public EmployeeShiftBreakPeriodDto changeDefault(Long shiftId, ChangeShiftBreakRequest request) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + shiftId));
        if (request.isSuppressed()) {
            throw new IllegalArgumentException(
                    "Podrazumevana pauza se ne isključuje — zatvorite je (close).");
        }
        validate(request);

        EmployeeShiftBreakPeriod open = repository
                .findOpenDefaultFor(shiftId, request.getBreakNo()).orElse(null);

        if (open != null && open.getValidFrom().equals(request.getValidFrom())) {
            apply(open, request);
            return EmployeeShiftBreakPeriodDto.from(repository.saveAndFlush(open));
        }

        if (open != null) {
            if (!request.getValidFrom().isAfter(open.getValidFrom())) {
                throw new IllegalArgumentException(
                        "Nova podrazumevana pauza mora da počne posle početka trenutne ("
                                + open.getValidFrom() + ").");
            }
            open.setValidTo(request.getValidFrom().minusDays(1));
            repository.saveAndFlush(open);
        }

        EmployeeShiftBreakPeriod created = repository.saveAndFlush(EmployeeShiftBreakPeriod.builder()
                .shift(shift)
                .breakNo(request.getBreakNo())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(request.getNote())
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.info("Shift {} default break {} set to {}–{} from {}",
                shift.getShiftCode(), request.getBreakNo(),
                request.getStartTime(), request.getEndTime(), request.getValidFrom());
        return EmployeeShiftBreakPeriodDto.from(created);
    }

    /** Close the open DEFAULT spell of one break — the shift stops having it after endedOn. */
    @Transactional
    public void closeDefault(Long shiftId, Integer breakNo, LocalDate endedOn) {
        EmployeeShiftBreakPeriod open = repository.findOpenDefaultFor(shiftId, breakNo)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Smena nema otvorenu podrazumevanu pauzu br. " + breakNo + "."));
        if (endedOn.isBefore(open.getValidFrom())) {
            throw new IllegalArgumentException("Kraj ne može biti pre početka (" + open.getValidFrom() + ").");
        }
        open.setValidTo(endedOn);
        repository.saveAndFlush(open);
    }

    private static void apply(EmployeeShiftBreakPeriod period, ChangeShiftBreakRequest request) {
        period.setSuppressed(request.isSuppressed());
        period.setStartTime(request.getStartTime());
        period.setEndTime(request.getEndTime());
        period.setValidTo(request.getValidTo());
        period.setNote(request.getNote());
    }

    private static void validate(ChangeShiftBreakRequest request) {
        if (request.getValidTo() != null && request.getValidTo().isBefore(request.getValidFrom())) {
            throw new IllegalArgumentException("Datum \"do\" ne može biti pre datuma \"od\".");
        }
        if (request.isSuppressed()) {
            if (request.getStartTime() != null || request.getEndTime() != null) {
                throw new IllegalArgumentException("Isključena pauza nema termin — uklonite vremena.");
            }
            return;
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new IllegalArgumentException("Vreme početka i kraja pauze je obavezno.");
        }
        if (request.getStartTime().equals(request.getEndTime())) {
            throw new IllegalArgumentException("Početak i kraj pauze ne mogu biti isti.");
        }
    }

    static String breakLabel(Integer breakNo) {
        return switch (breakNo) {
            case 1 -> "prvu pauzu";
            case 2 -> "drugu pauzu";
            case 3 -> "treću pauzu";
            default -> breakNo + ". pauzu";
        };
    }
}
