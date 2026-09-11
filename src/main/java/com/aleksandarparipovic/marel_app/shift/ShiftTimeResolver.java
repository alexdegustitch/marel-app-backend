package com.aleksandarparipovic.marel_app.shift;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * THE one answer to "when does this shift run for this person on this date".
 *
 * <p>Employee's own period → the versioned default period → the shifts row
 * itself (data older than the period table, or inserted around it). Every
 * writer of {@code work_shifts.start_at/end_at} derives from here, so a
 * worker's custom hours and a rewound default both reach the karton, the
 * boundary recalculation and the leave sweep through a single seam.
 */
@Service
@RequiredArgsConstructor
public class ShiftTimeResolver {

    private final EmployeeShiftTimePeriodRepository periodRepository;

    /** When the shift runs, and whether that is the employee's own arrangement. */
    public record ResolvedShiftTimes(LocalTime startTime, LocalTime endTime, boolean employeeOwn) {}

    @Transactional(readOnly = true)
    public ResolvedShiftTimes resolve(Long employeeId, Shift shift, LocalDate date) {
        return periodRepository.findInForce(employeeId, shift.getId(), date)
                .map(p -> new ResolvedShiftTimes(p.getStartTime(), p.getEndTime(), p.getEmployee() != null))
                .orElseGet(() -> new ResolvedShiftTimes(shift.getStartTime(), shift.getEndTime(), false));
    }
}
