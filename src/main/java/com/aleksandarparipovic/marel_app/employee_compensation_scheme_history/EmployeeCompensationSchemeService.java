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
import java.time.OffsetDateTime;
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

        final LocalDate appliesFrom = normalizeToMonthBoundary(effectiveFrom, periods);

        EmployeeCompensationSchemeHistory covering = periods.stream()
                .filter(p -> p.coversInclusive(appliesFrom))
                .findFirst()
                .orElse(null);

        // A period starting later does NOT block the change any more. The new
        // period is inserted BETWEEN: the covering one is cut short before it and
        // the new one ends the day before the next one begins.
        //
        // This used to refuse, on the reasoning that a later period would be
        // orphaned. It is not — it keeps its own dates untouched; only the gap in
        // front of it is filled. Refusing meant an employee with any future
        // scheme change could not have an earlier month corrected at all.
        EmployeeCompensationSchemeHistory successor = periods.stream()
                .filter(p -> p.getValidFrom().isAfter(appliesFrom))
                .min(java.util.Comparator.comparing(EmployeeCompensationSchemeHistory::getValidFrom))
                .orElse(null);

        // A period that starts on exactly this date is REPLACED in place: its
        // scheme changes, its dates do not. Refusing here meant an administrator
        // who picked the 13th — normalised to the 1st, where a period already
        // began — was told to go and fix it by hand, with nothing on the screen
        // to fix it with.
        EmployeeCompensationSchemeHistory sameStart = periods.stream()
                .filter(p -> p.getValidFrom().equals(appliesFrom))
                .findFirst()
                .orElse(null);

        if (sameStart != null) {
            if (sameStart.getCompensationScheme().getId().equals(scheme.getId())) {
                throw new ConflictException(
                        "Zaposleni već koristi način obračuna \"" + scheme.getName()
                                + "\" od " + appliesFrom + ".");
            }
            sameStart.setCompensationScheme(scheme);
            if (note != null) {
                sameStart.setNote(note);
            }
            EmployeeCompensationSchemeHistory replaced = historyRepository.saveAndFlush(sameStart);

            // The same invalidation the new-period path uses, not a second
            // mechanism: a replaced period changes what that range is worth
            // exactly as a new one does.
            invalidateFrom(employeeId, appliesFrom, "COMPENSATION_SCHEME_CHANGE");

            log.info("Employee {} scheme replaced in place from {} with {} (history id {})",
                    employeeId, appliesFrom, scheme.getCode(), replaced.getId());
            return replaced;
        }

        if (covering != null) {
            if (covering.getCompensationScheme().getId().equals(scheme.getId())) {
                throw new ConflictException(
                        "Zaposleni već koristi način obračuna \"" + scheme.getName() + "\" na datum " + appliesFrom + ".");
            }
            if (!covering.getValidFrom().isBefore(appliesFrom)) {
                // Closing it would produce valid_until < valid_from.
                throw new ConflictException(
                        "Novi period mora počinjati posle " + covering.getValidFrom()
                                + ", početka trenutnog perioda obračuna.");
            }
            // Close the open period the day before the new one starts. Inclusive
            // end date, so the two periods touch without overlapping and the
            // transition day itself already belongs to the new scheme.
            covering.setValidUntil(appliesFrom.minusDays(1));

            // saveAndFlush, NOT save. Hibernate orders INSERTs before UPDATEs inside
            // a flush, and IDENTITY generation forces the INSERT out the moment the
            // new period is saved — so a plain save() here left the old period still
            // open in the database when the new one arrived, and ex_ecsh_no_overlap
            // rejected every scheme change for an employee who already had one.
            // Which is every employee. The close has to reach the database first.
            historyRepository.saveAndFlush(covering);
        }

        EmployeeCompensationSchemeHistory created = historyRepository.save(
                EmployeeCompensationSchemeHistory.builder()
                        .employee(employee)
                        .compensationScheme(scheme)
                        .validFrom(appliesFrom)
                        // Ends the day before the next period, or stays open when
                        // there is none. Without this the insert would overlap the
                        // successor and ex_ecsh_no_overlap would reject it.
                        .validUntil(successor == null ? null : successor.getValidFrom().minusDays(1))
                        .note(note)
                        .build());

        invalidateFrom(employeeId, appliesFrom, "COMPENSATION_SCHEME_CHANGE");

        log.info("Employee {} moved to compensation scheme {} from {} (history id {})",
                employeeId, scheme.getCode(), appliesFrom, created.getId());

        return created;
    }

    /**
     * A scheme change takes effect on the first day of a month, and not this one (D1).
     *
     * <p>An employee must have exactly ONE scheme in any payroll month. A change
     * dated mid-month would split the month in two, and
     * {@link com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScopeService}
     * refuses to calculate that rather than merge the two policies — so the rule
     * has to hold here, where somebody can still be told about it.
     *
     * <p>Refused rather than snapped forward: "from 15 September" and "from 1
     * October" are different requests, and quietly turning one into the other means
     * the confirmation screen shows a date the system did not use.
     *
     * <p>The FIRST assignment is exempt. A new employee starts on their hire date,
     * whatever day of the month that is — that is not a change, it is the beginning
     * of the history, and there is no earlier month for it to split.
     */
    private LocalDate normalizeToMonthBoundary(LocalDate effectiveFrom,
                                               List<EmployeeCompensationSchemeHistory> periods) {
        if (periods.isEmpty()) {
            return effectiveFrom;
        }

        // A payroll month must hold exactly ONE scheme — PayrollSchemeScopeService
        // throws otherwise and the month's payslip cannot be produced at all. So a
        // mid-month date is not refused, it is moved to the FIRST OF ITS OWN
        // MONTH: picking the 20th of August means August is calculated under the
        // new scheme, not September.
        LocalDate firstOfItsMonth = effectiveFrom.withDayOfMonth(1);

        // Never further back than the month being calculated now. Reaching into a
        // closed month would re-price payroll nobody expected to move.
        LocalDate firstOfThisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate normalized = firstOfItsMonth.isBefore(firstOfThisMonth)
                ? firstOfThisMonth
                : firstOfItsMonth;

        if (!normalized.equals(effectiveFrom)) {
            log.info("Scheme change requested from {} moved to {} — a payroll month takes one scheme",
                    effectiveFrom, normalized);
        }
        return normalized;
    }

    /**
     * Replace a scheme change that was scheduled but has not taken effect yet.
     *
     * <p>Separate from {@link #changeScheme}, which refuses when a later period
     * exists. That refusal is right for the ordinary path — a future decision the
     * user cannot see on this screen must not disappear because of an edit to the
     * present — but somebody has to be able to correct a mistake, and doing it by
     * hand in the database is worse.
     *
     * <p>The superseded period is ARCHIVED, not deleted: what was scheduled, and
     * that somebody changed their mind, are both part of the record.
     */
    @Transactional
    public EmployeeCompensationSchemeHistory replaceScheduledChange(Long employeeId,
                                                                    Long schemeId,
                                                                    LocalDate effectiveFrom,
                                                                    String note) {
        requireEmployee(employeeId);
        LocalDate today = LocalDate.now();

        List<EmployeeCompensationSchemeHistory> scheduled =
                historyRepository.lockHistoryFor(employeeId).stream()
                        .filter(p -> p.getValidFrom().isAfter(today))
                        .toList();

        if (scheduled.isEmpty()) {
            throw new ConflictException(
                    "Nema zakazane promene načina obračuna koja bi se zamenila.");
        }

        for (EmployeeCompensationSchemeHistory period : scheduled) {
            period.setArchivedAt(OffsetDateTime.now());
            historyRepository.save(period);
            log.info("Archived scheduled scheme period {} for employee {} (superseded)",
                    period.getId(), employeeId);
        }
        historyRepository.flush();

        // Whatever was closed to make room for the archived period is open again:
        // its valid_until pointed at a change that no longer happens.
        historyRepository.lockHistoryFor(employeeId).stream()
                .filter(p -> p.getArchivedAt() == null)
                .filter(p -> p.getValidUntil() != null && !p.getValidUntil().isBefore(today))
                .forEach(p -> {
                    p.setValidUntil(null);
                    historyRepository.save(p);
                });
        historyRepository.flush();

        return changeScheme(employeeId, schemeId, effectiveFrom, note);
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
