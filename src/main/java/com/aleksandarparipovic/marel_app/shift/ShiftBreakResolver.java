package com.aleksandarparipovic.marel_app.shift;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE one answer to "when are this person's pauze on this shift and date".
 *
 * <p>Per break (breakNo): the employee's own period → the versioned default
 * period → nothing. A suppressed personal period REMOVES the default break for
 * its dates. There is deliberately no fallback beyond the default row (the way
 * {@link ShiftTimeResolver} falls back to the shifts row): a break nobody
 * configured does not exist, and nothing should invent one.
 */
@Service
@RequiredArgsConstructor
public class ShiftBreakResolver {

    private final EmployeeShiftBreakPeriodRepository periodRepository;

    /** One break window, and whether it is the employee's own arrangement. */
    public record ResolvedBreak(int breakNo, LocalTime startTime, LocalTime endTime, boolean employeeOwn) {}

    /** Every break of the shift in force for this person on this date, by breakNo. */
    @Transactional(readOnly = true)
    public List<ResolvedBreak> resolveAll(Long employeeId, Long shiftId, LocalDate date) {
        Map<Integer, EmployeeShiftBreakPeriod> byBreakNo = new LinkedHashMap<>();
        for (EmployeeShiftBreakPeriod p : periodRepository.findAllInForce(employeeId, shiftId, date)) {
            EmployeeShiftBreakPeriod chosen = byBreakNo.get(p.getBreakNo());
            // The employee's row wins within its breakNo; rows arrive ordered
            // by breakNo, so the map keeps that order.
            if (chosen == null || (chosen.getEmployee() == null && p.getEmployee() != null)) {
                byBreakNo.put(p.getBreakNo(), p);
            }
        }
        List<ResolvedBreak> resolved = new ArrayList<>();
        for (EmployeeShiftBreakPeriod p : byBreakNo.values()) {
            if (p.isSuppressed()) {
                continue; // this person does not take that break
            }
            resolved.add(new ResolvedBreak(
                    p.getBreakNo(), p.getStartTime(), p.getEndTime(), p.getEmployee() != null));
        }
        return resolved;
    }
}
