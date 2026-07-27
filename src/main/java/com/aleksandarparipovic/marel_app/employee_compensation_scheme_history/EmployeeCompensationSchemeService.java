package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.report_worker.DailyRecalcRequestedEvent;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Manages an employee's compensation-scheme history.
 *
 * <p>The one rule that shapes everything here: <b>history is appended, never
 * rewritten.</b> Moving an employee to a different scheme closes the currently
 * open period and inserts a new one. It never edits an existing row's scheme, so
 * work already recorded keeps the policy that was actually applied to it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeCompensationSchemeService {

    private final EmployeeCompensationSchemeHistoryRepository historyRepository;
    private final CompensationSchemeRepository schemeRepository;
    private final EmployeeRepository employeeRepository;
    private final WorkShiftRepository workShiftRepository;
    private final RecalcQueueService recalcQueueService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<EmployeeCompensationSchemeHistory> getHistory(Long employeeId) {
        requireEmployee(employeeId);
        return historyRepository.findHistoryFor(employeeId);
    }

    /**
     * Move an employee onto {@code schemeId} with effect from {@code effectiveFrom}.
     *
     * <p>Transactional and row-locked. The {@code ex_ecsh_no_overlap} exclusion
     * constraint is the real guarantee against overlapping periods; the lock
     * exists so two concurrent changes for the same employee serialise into a
     * clean sequential result rather than one of them dying on a constraint
     * violation the user cannot act on.
     *
     * <p>Work from {@code effectiveFrom} onward is queued for recalculation —
     * only this employee, only from that date. Earlier periods resolve to the
     * unchanged earlier scheme and are deliberately left alone. Locked payroll is
     * protected by the existing mechanism: {@code PayrollRunItemService} never
     * refreshes a LOCKED item, so a recalculated monthly report cannot move a
     * locked amount.
     */
    @Transactional
    public EmployeeCompensationSchemeHistory changeScheme(Long employeeId,
                                                         Long schemeId,
                                                         LocalDate effectiveFrom,
                                                         String note) {
        Employee employee = requireEmployee(employeeId);

        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Datum početka primene je obavezan.");
        }

        CompensationScheme scheme = schemeRepository.findById(schemeId)
                .orElseThrow(() -> new EntityNotFoundException("Način obračuna ne postoji: " + schemeId));
        if (!scheme.isUsable()) {
            throw new IllegalArgumentException(
                    "Način obračuna \"" + scheme.getName() + "\" nije aktivan i ne može se dodeliti.");
        }

        // Locks this employee's periods for the rest of the transaction.
        List<EmployeeCompensationSchemeHistory> periods = historyRepository.lockHistoryFor(employeeId);

        EmployeeCompensationSchemeHistory covering = periods.stream()
                .filter(p -> p.coversInclusive(effectiveFrom))
                .findFirst()
                .orElse(null);

        // Anything starting on or after the new date would be orphaned or would
        // overlap. Refuse rather than silently deleting or truncating periods the
        // user cannot see.
        boolean hasLaterPeriod = periods.stream()
                .anyMatch(p -> !p.getValidFrom().isBefore(effectiveFrom));
        if (hasLaterPeriod) {
            throw new ConflictException(
                    "Već postoji period obračuna koji počinje na ili posle " + effectiveFrom
                            + ". Obrišite ili ispravite taj period pre dodavanja novog.");
        }

        if (covering != null) {
            if (covering.getCompensationScheme().getId().equals(scheme.getId())) {
                throw new ConflictException(
                        "Zaposleni već koristi način obračuna \"" + scheme.getName() + "\" na datum " + effectiveFrom + ".");
            }
            if (!covering.getValidFrom().isBefore(effectiveFrom)) {
                // Closing it would produce valid_until < valid_from.
                throw new ConflictException(
                        "Novi period mora počinjati posle " + covering.getValidFrom()
                                + ", početka trenutnog perioda obračuna.");
            }
            // Close the open period the day before the new one starts. Inclusive
            // end date, so the two periods touch without overlapping and the
            // transition day itself already belongs to the new scheme.
            covering.setValidUntil(effectiveFrom.minusDays(1));
            historyRepository.save(covering);
        }

        EmployeeCompensationSchemeHistory created = historyRepository.save(
                EmployeeCompensationSchemeHistory.builder()
                        .employee(employee)
                        .compensationScheme(scheme)
                        .validFrom(effectiveFrom)
                        .validUntil(null)
                        .note(note)
                        .build());

        invalidateFrom(employeeId, effectiveFrom, "COMPENSATION_SCHEME_CHANGE");

        log.info("Employee {} moved to compensation scheme {} from {} (history id {})",
                employeeId, scheme.getCode(), effectiveFrom, created.getId());

        return created;
    }

    /**
     * Queue recalculation for one employee's work from {@code fromDate} onward.
     *
     * <p>Scoped as narrowly as the change itself: one employee, one open-ended
     * date range, using the existing daily queue so the existing debounce,
     * retry and monthly-cascade behaviour applies unchanged. No unrelated
     * employee and no earlier period is invalidated.
     */
    void invalidateFrom(Long employeeId, LocalDate fromDate, String reason) {
        List<WorkShift> affected = workShiftRepository.findActiveByEmployeeFromDate(employeeId, fromDate);
        if (affected.isEmpty()) {
            return;
        }
        for (WorkShift shift : affected) {
            recalcQueueService.enqueueDailyJob(shift, reason);
        }
        eventPublisher.publishEvent(new DailyRecalcRequestedEvent(DailyRecalcRequestedEvent.Type.DAILY));
        log.info("Queued {} shift(s) of employee {} for recalculation from {} ({})",
                affected.size(), employeeId, fromDate, reason);
    }

    private Employee requireEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Zaposleni ne postoji: " + employeeId));
    }
}
