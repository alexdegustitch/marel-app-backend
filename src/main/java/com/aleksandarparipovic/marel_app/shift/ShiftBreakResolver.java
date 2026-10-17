package com.aleksandarparipovic.marel_app.shift;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * THE one answer to "when is this person's pauza on this shift and date".
 *
 * <p>Employee's own period → the versioned default period → EMPTY. There is
 * deliberately no third fallback (the way {@link ShiftTimeResolver} falls back
 * to the shifts row): a shift whose break nobody configured has no break, and
 * nothing should invent one.
 */
@Service
@RequiredArgsConstructor
public class ShiftBreakResolver {

    private final EmployeeShiftBreakPeriodRepository periodRepository;

    /** The break window, and whether it is the employee's own arrangement. */
    public record ResolvedBreak(LocalTime startTime, LocalTime endTime, boolean employeeOwn) {}

    @Transactional(readOnly = true)
    public Optional<ResolvedBreak> resolve(Long employeeId, Long shiftId, LocalDate date) {
        return periodRepository.findInForce(employeeId, shiftId, date)
                .map(p -> new ResolvedBreak(p.getStartTime(), p.getEndTime(), p.getEmployee() != null));
    }
}
