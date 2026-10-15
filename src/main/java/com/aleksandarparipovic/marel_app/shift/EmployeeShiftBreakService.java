package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftTimeRequest;
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
 * shift's defaults alike. The exact sibling of {@link EmployeeShiftTimeService}
 * / the default handling in {@code ShiftAdminService}, with the same
 * transition semantics: the previous spell is CLOSED the day before the new
 * one begins, never edited to say something else; the same start date is a
 * CORRECTION of the same spell.
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

    /** Give the employee their own break window for a shift, from a date. */
    @Transactional
    public EmployeeShiftBreakPeriodDto change(Long employeeId, ChangeShiftTimeRequest request) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Zaposleni ne postoji: " + employeeId));
        Shift shift = shiftRepository.findById(request.getShiftId())
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + request.getShiftId()));

        validate(request);

        EmployeeShiftBreakPeriod open = repository.findOpenFor(employeeId, shift.getId()).orElse(null);

        if (open != null && open.getValidFrom().equals(request.getValidFrom())) {
            // The same start date is a CORRECTION — no second version opens.
            open.setStartTime(request.getStartTime());
            open.setEndTime(request.getEndTime());
            open.setValidTo(request.getValidTo());
            open.setNote(request.getNote());
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
                employeeId, shift.getId(), request.getValidFrom(), request.getValidTo(),
                open != null ? open.getId() : -1L);
        if (!clashing.isEmpty()) {
            throw new ConflictException(
                    "Za smenu " + shift.getShiftCode() + " već postoji pauza koja se preklapa sa unetim periodom ("
                            + clashing.getFirst().getValidFrom()
                            + (clashing.getFirst().getValidTo() != null ? " – " + clashing.getFirst().getValidTo() : " – ")
                            + ").");
        }

        EmployeeShiftBreakPeriod created = repository.saveAndFlush(EmployeeShiftBreakPeriod.builder()
                .employee(employee)
                .shift(shift)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(request.getNote())
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.debug("Employee {} breaks on shift {} at {}–{} from {}",
                employeeId, shift.getShiftCode(), request.getStartTime(), request.getEndTime(),
                request.getValidFrom());

        return EmployeeShiftBreakPeriodDto.from(created);
    }

    /** Close the open spell — the default applies again from the next day. */
    @Transactional
    public void close(Long employeeId, Long shiftId, LocalDate endedOn) {
        EmployeeShiftBreakPeriod open = repository.findOpenFor(employeeId, shiftId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Zaposleni nema otvorenu sopstvenu pauzu za tu smenu."));
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

    // ── The shift's DEFAULT break — the šifarnik's side ──────────────────────

    /** The default-break history of one shift, newest first. */
    @Transactional(readOnly = true)
    public List<EmployeeShiftBreakPeriodDto> defaultHistory(Long shiftId) {
        return repository.findDefaultHistoryFor(shiftId).stream()
                .map(EmployeeShiftBreakPeriodDto::from)
                .toList();
    }

    /**
     * Move the shift's default break from a date — or state it for the first
     * time. Same correction/versioning rules as the employee spell.
     */
    @Transactional
    public EmployeeShiftBreakPeriodDto changeDefault(Long shiftId, ChangeShiftTimeRequest request) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + shiftId));
        validate(request);

        EmployeeShiftBreakPeriod open = repository.findOpenDefaultFor(shiftId).orElse(null);

        if (open != null && open.getValidFrom().equals(request.getValidFrom())) {
            open.setStartTime(request.getStartTime());
            open.setEndTime(request.getEndTime());
            open.setValidTo(request.getValidTo());
            open.setNote(request.getNote());
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
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(request.getNote())
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.info("Shift {} default break set to {}–{} from {}",
                shift.getShiftCode(), request.getStartTime(), request.getEndTime(), request.getValidFrom());
        return EmployeeShiftBreakPeriodDto.from(created);
    }

    /** Close the open DEFAULT spell — the shift has no break after endedOn. */
    @Transactional
    public void closeDefault(Long shiftId, LocalDate endedOn) {
        EmployeeShiftBreakPeriod open = repository.findOpenDefaultFor(shiftId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Smena nema otvorenu podrazumevanu pauzu."));
        if (endedOn.isBefore(open.getValidFrom())) {
            throw new IllegalArgumentException("Kraj ne može biti pre početka (" + open.getValidFrom() + ").");
        }
        open.setValidTo(endedOn);
        repository.saveAndFlush(open);
    }

    private static void validate(ChangeShiftTimeRequest request) {
        if (request.getStartTime().equals(request.getEndTime())) {
            throw new IllegalArgumentException("Početak i kraj pauze ne mogu biti isti.");
        }
        if (request.getValidTo() != null && request.getValidTo().isBefore(request.getValidFrom())) {
            throw new IllegalArgumentException("Datum \"do\" ne može biti pre datuma \"od\".");
        }
    }
}
