package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.shift.dto.ChangeShiftTimeRequest;
import com.aleksandarparipovic.marel_app.shift.dto.EffectiveShiftTimeDto;
import com.aleksandarparipovic.marel_app.shift.dto.EmployeeShiftTimePeriodDto;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * An employee's own hours for a shift, kept as dated spells.
 *
 * <p>NOTHING HERE REWRITES A WORK SHIFT ALREADY WRITTEN. The rows only change
 * what the next created shift (and the boundary recalculation's baseline) is
 * seeded with — the karton rows stay the material truth payroll reads.
 * Transition semantics match the work-category periods: the previous spell is
 * CLOSED the day before the new one begins, never edited to say something
 * else; the same start date is a CORRECTION of the same spell.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeShiftTimeService {

    private final EmployeeShiftTimePeriodRepository repository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftTimeResolver shiftTimeResolver;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public List<EmployeeShiftTimePeriodDto> history(Long employeeId) {
        return repository.findHistoryFor(employeeId).stream()
                .map(EmployeeShiftTimePeriodDto::from)
                .toList();
    }

    /** When each active shift runs for this employee on a date. */
    @Transactional(readOnly = true)
    public List<EffectiveShiftTimeDto> effective(Long employeeId, LocalDate date) {
        return shiftRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByStartTimeAsc().stream()
                .map(shift -> {
                    var resolved = shiftTimeResolver.resolve(employeeId, shift, date);
                    return new EffectiveShiftTimeDto(
                            shift.getId(),
                            shift.getShiftCode(),
                            shift.getName(),
                            resolved.startTime(),
                            resolved.endTime(),
                            resolved.employeeOwn());
                })
                .toList();
    }

    /**
     * Give the employee their own hours for a shift, from a date.
     *
     * <p>An open spell starting the SAME day is corrected in place; one
     * starting earlier is closed the day before. A spell may also carry an
     * explicit end (validTo), after which the default applies again.
     */
    @Transactional
    public EmployeeShiftTimePeriodDto change(Long employeeId, ChangeShiftTimeRequest request) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Zaposleni ne postoji: " + employeeId));
        Shift shift = shiftRepository.findById(request.getShiftId())
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + request.getShiftId()));

        if (request.getStartTime().equals(request.getEndTime())) {
            throw new IllegalArgumentException("Početak i kraj smene ne mogu biti isti.");
        }
        if (request.getValidTo() != null && request.getValidTo().isBefore(request.getValidFrom())) {
            throw new IllegalArgumentException("Datum \"do\" ne može biti pre datuma \"od\".");
        }

        EmployeeShiftTimePeriod open = repository.findOpenFor(employeeId, shift.getId()).orElse(null);

        if (open != null && open.getValidFrom().equals(request.getValidFrom())) {
            // The same start date is a CORRECTION — the spell never really ran
            // with the mistyped hours, so no second version is opened.
            open.setStartTime(request.getStartTime());
            open.setEndTime(request.getEndTime());
            open.setValidTo(request.getValidTo());
            open.setNote(request.getNote());
            return EmployeeShiftTimePeriodDto.from(repository.saveAndFlush(open));
        }

        if (open != null) {
            if (!request.getValidFrom().isAfter(open.getValidFrom())) {
                throw new IllegalArgumentException(
                        "Novo radno vreme mora da počne posle početka trenutnog ("
                                + open.getValidFrom() + ").");
            }
            // Inclusive end, so the day BEFORE the new spell begins.
            open.setValidTo(request.getValidFrom().minusDays(1));
            repository.saveAndFlush(open);
        }

        List<EmployeeShiftTimePeriod> clashing = repository.findOwnOverlapping(
                employeeId, shift.getId(), request.getValidFrom(), request.getValidTo(),
                open != null ? open.getId() : -1L);
        if (!clashing.isEmpty()) {
            throw new ConflictException(
                    "Za smenu " + shift.getShiftCode() + " već postoji radno vreme koje se preklapa sa unetim periodom ("
                            + clashing.getFirst().getValidFrom()
                            + (clashing.getFirst().getValidTo() != null ? " – " + clashing.getFirst().getValidTo() : " – ")
                            + ").");
        }

        EmployeeShiftTimePeriod created = repository.saveAndFlush(EmployeeShiftTimePeriod.builder()
                .employee(employee)
                .shift(shift)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(request.getNote())
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        log.debug("Employee {} works shift {} at {}–{} from {}",
                employeeId, shift.getShiftCode(), request.getStartTime(), request.getEndTime(),
                request.getValidFrom());

        return EmployeeShiftTimePeriodDto.from(created);
    }

    /** Close the open spell — the default applies again from the next day. */
    @Transactional
    public void close(Long employeeId, Long shiftId, LocalDate endedOn) {
        EmployeeShiftTimePeriod open = repository.findOpenFor(employeeId, shiftId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Zaposleni nema otvoreno sopstveno radno vreme za tu smenu."));
        if (endedOn.isBefore(open.getValidFrom())) {
            throw new IllegalArgumentException("Kraj ne može biti pre početka (" + open.getValidFrom() + ").");
        }
        open.setValidTo(endedOn);
        repository.saveAndFlush(open);
    }

    /** Withdraw a spell that should never have existed. Archived, not deleted. */
    @Transactional
    public void archive(Long employeeId, Long periodId) {
        EmployeeShiftTimePeriod period = repository.findById(periodId)
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
}
