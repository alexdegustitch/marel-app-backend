package com.aleksandarparipovic.marel_app.employment_period;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The only place employment periods are written.
 *
 * <p>{@code employees.employment_start_date} and {@code employment_end_date} are
 * mirrors kept by a database trigger. Writing them directly would be overwritten
 * by the next period change and, until then, would show a date the periods do not
 * agree with — so every path that used to set them goes through here instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmploymentPeriodService {

    private final EmployeeEmploymentPeriodRepository repository;
    private final WorkLogRepository workLogRepository;

    /**
     * Whether the employment start date may still be moved.
     *
     * <p>It may, until real work is recorded: before the employee has started,
     * and after they have started for as long as no WORK log exists. From the
     * first WORK log on, the start date is the anchor the shifts, norms and
     * payroll behind it were calculated against, and moving it would silently
     * restate all of them.
     */
    @Transactional(readOnly = true)
    public boolean canEditEmploymentStart(Long employeeId) {
        return employeeId != null && !workLogRepository.existsWorkTypeLogForEmployee(employeeId);
    }

    /**
     * Opens an employee's FIRST spell, with their own {@code norm_grace_days} as
     * the probation length.
     *
     * <p>The first period is the one that gets the employee-level default (30).
     * Every later one defaults to zero, because a returning employee serves no
     * new probation unless somebody says so.
     *
     * <p>Idempotent: an employee who already has a period keeps it, so a re-run
     * or a retried creation cannot open a second overlapping spell — which
     * {@code ex_eep_no_overlap} would refuse anyway, with an error the caller
     * could do nothing about.
     */
    @Transactional
    public Optional<EmployeeEmploymentPeriod> openFirstPeriod(Employee employee) {
        if (employee == null || employee.getId() == null || employee.getEmploymentStartDate() == null) {
            return Optional.empty();
        }
        if (repository.findLatestOne(employee.getId()).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(repository.saveAndFlush(EmployeeEmploymentPeriod.builder()
                .employee(employee)
                .startedOn(employee.getEmploymentStartDate())
                .endedOn(employee.getEmploymentEndDate())
                .normGraceDays(employee.getNormGraceDays() == null ? 0 : employee.getNormGraceDays())
                .note("Opened on employee creation.")
                .build()));
    }

    /**
     * Applies edited employment dates to the employee's CURRENT spell.
     *
     * <p>The employee screen edits "the employment start and end date", and with a
     * single spell — which is every employee today — that means exactly what it
     * always did. Adding a spell is a different action and deliberately not this
     * one: overwriting the current period's start with a rehire date would erase
     * the first spell, which is the thing this table exists to stop.
     *
     * <p>Nulls leave the corresponding date alone, matching the patch semantics
     * the callers already have.
     */
    @Transactional
    public void applyEditedDates(Long employeeId, LocalDate startedOn, LocalDate endedOn) {
        if (employeeId == null || (startedOn == null && endedOn == null)) {
            return;
        }
        repository.findLatestOne(employeeId).ifPresentOrElse(period -> {
            // Unchanged is not a change. The employee screen sends the whole
            // form on every save, so a start date equal to the stored one must
            // not trip the guard below — otherwise editing a phone number would
            // be refused for everyone who has ever worked.
            boolean startMoves = startedOn != null && !startedOn.equals(period.getStartedOn());
            if (startMoves && !canEditEmploymentStart(employeeId)) {
                throw new IllegalArgumentException(
                        "Početak rada se ne može promeniti jer za radnika već postoji evidentiran rad. "
                                + "Datum je osnova po kojoj su obračunate smene i norme posle njega.");
            }
            if (startedOn != null) period.setStartedOn(startedOn);
            if (endedOn != null) period.setEndedOn(endedOn);
            repository.saveAndFlush(period);
        }, () -> log.warn(
                "Employee {} has no employment period; edited dates were not applied. "
                        + "Every employee should have one from 2026-09-16-01.", employeeId));
    }

    /**
     * Change how long probation lasts on the CURRENT spell.
     *
     * <p>Writes the period, not {@code employees.norm_grace_days} — that column
     * is a trigger-maintained mirror, and {@link com.aleksandarparipovic.marel_app.employee.ProbationPolicy}
     * reads {@code employee_employment_periods.probation_end_date}, which is
     * generated from the PERIOD's own value. Setting the employee column alone
     * changed nothing at all, which is how this went unnoticed.
     *
     * <p>Returns the range whose calculation may have moved: probation credits
     * performance at 100 %, so both lengthening and shortening it change what
     * shifts in the affected window are worth. Shortening exposes shifts that
     * were credited and no longer should be; lengthening covers shifts that were
     * measured and now should not be.
     */
    @Transactional
    public Optional<LocalDate[]> changeProbationDays(Long employeeId, int normGraceDays) {
        if (normGraceDays < 0) {
            throw new IllegalArgumentException("Trajanje probnog perioda ne može biti negativno.");
        }
        return repository.findLatestOne(employeeId).flatMap(period -> {
            // Same reason as applyEditedDates: the form always sends this field,
            // so an unchanged value must stay a no-op rather than be validated.
            Integer stored = period.getNormGraceDays();
            if (stored != null && stored == normGraceDays) {
                return Optional.<LocalDate[]>empty();
            }

            LocalDate today = LocalDate.now();
            LocalDate oldEnd = period.getProbationEndDate();

            // Only while probation is still running. Once it is over, the days
            // it covered have already been credited at 100 % and paid; changing
            // its length then is a rewrite of settled months, not an edit.
            if (oldEnd == null || oldEnd.isBefore(today)) {
                throw new IllegalArgumentException(
                        "Trajanje probnog perioda se može menjati samo dok probni period traje."
                                + (oldEnd == null ? "" : " Probni period je završen " + oldEnd + "."));
            }

            // It may be extended freely, but not shortened into the past: that
            // would declare that days the employee has already served were not
            // probation after all, and re-price them backwards.
            LocalDate newEnd = period.getStartedOn().plusDays(normGraceDays);
            if (newEnd.isBefore(today)) {
                long minDays = java.time.temporal.ChronoUnit.DAYS.between(period.getStartedOn(), today);
                throw new IllegalArgumentException(
                        "Probni period se ne može skratiti unazad — dani koji su već odrađeni "
                                + "na probnom radu ostaju probni. Najmanje moguće trajanje je "
                                + minDays + " dana (do danas).");
            }

            period.setNormGraceDays(normGraceDays);
            repository.saveAndFlush(period);

            // Everything between the old and the new end changed meaning; the
            // earlier of the two is where recalculation has to start.
            LocalDate from = oldEnd == null || newEnd.isBefore(oldEnd) ? newEnd : oldEnd;
            LocalDate to = oldEnd == null || newEnd.isAfter(oldEnd) ? newEnd : oldEnd;
            log.info("Employee {} probation changed to {} days; affected {} – {}",
                    employeeId, normGraceDays, from, to);
            return Optional.of(new LocalDate[]{from, to});
        });
    }

    /** The spell covering this date, if the employee was employed then. */
    @Transactional(readOnly = true)
    public Optional<EmployeeEmploymentPeriod> periodOn(Long employeeId, LocalDate date) {
        if (employeeId == null || date == null) {
            return Optional.empty();
        }
        return repository.findCoveringOne(employeeId, date);
    }
}
