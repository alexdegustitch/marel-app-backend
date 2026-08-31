package com.aleksandarparipovic.marel_app.overtime_record;

import com.aleksandarparipovic.marel_app.employee.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Keeps one day's overtime row in step with what that day's reports say.
 *
 * <p>Called from the daily recalculation, after the report for a shift has been
 * written. It answers one question — how much did this employee work beyond a
 * regular day — and makes the table say that, whether it means inserting,
 * updating or removing a row.
 *
 * <p><b>Why the ND minutes come back out.</b> A neradni dan is written as a work
 * log across the whole shift, so it lands in {@code total_shift_minutes} like
 * any other log. Counted, a ten-hour shift covered by ND would report two hours
 * of overtime that nobody worked — and that overtime would go back into the bank
 * that paid for the ND in the first place. Subtracting them is what keeps the
 * bank from refilling itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OvertimeRecordService {

    /** A regular day. Everything above this is overtime; nothing below it is. */
    public static final int REGULAR_DAY_MINUTES = 480;

    private final OvertimeRecordRepository repository;
    private final OvertimeQueryRepository queryRepository;

    /**
     * Recomputes one employee-day and reports whether the answer moved.
     *
     * @return true when the day's overtime is now different from what was stored,
     *         which is the signal the month's allocation has to run again. A day
     *         that did not change must not enqueue anything, or every
     *         recalculation would queue a month and the two would chase each
     *         other.
     */
    @Transactional
    public boolean refreshForDay(Employee employee, LocalDate workDate) {
        int worked = queryRepository.workedMinutesExcludingNonWorkingDay(employee.getId(), workDate);
        int overtime = Math.max(0, worked - REGULAR_DAY_MINUTES);

        Optional<OvertimeRecord> existing = repository.findByEmployee_IdAndWorkDate(employee.getId(), workDate);

        if (overtime == 0) {
            if (existing.isEmpty()) {
                return false;
            }
            // The absence of a row IS the zero: chk_overtime_records_minutes_positive
            // refuses to store one, and a day that stopped being long has to stop
            // contributing to the bank rather than contribute nothing.
            repository.delete(existing.get());
            log.debug("Overtime cleared for employee {} on {}", employee.getId(), workDate);
            return true;
        }

        if (existing.isPresent()) {
            OvertimeRecord record = existing.get();
            if (record.getOvertimeMinutes() == overtime) {
                return false;
            }
            record.setOvertimeMinutes(overtime);
            repository.save(record);
            log.debug("Overtime for employee {} on {} is now {} min", employee.getId(), workDate, overtime);
            return true;
        }

        repository.save(OvertimeRecord.builder()
                .employee(employee)
                .workDate(workDate)
                .overtimeMinutes(overtime)
                .build());
        log.debug("Overtime for employee {} on {} recorded as {} min", employee.getId(), workDate, overtime);
        return true;
    }
}
