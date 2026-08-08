package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.employment_period.EmployeeEmploymentPeriod;
import com.aleksandarparipovic.marel_app.employment_period.EmploymentPeriodService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Whether an employee was on probation on a given work date.
 *
 * <p><b>One question, one home.</b> Probation withholds the weekend bonus (see
 * {@code work_code_category_mapping_types.applies_during_probation}), and it may
 * come to mean more later. Every caller asks here.
 *
 * <p><b>Asked by WORK DATE, never by today.</b> The same discipline the
 * compensation-scheme resolver follows: recalculating an old month must not
 * re-decide it against the calendar the recalculation happens to run on, or a
 * payslip would change meaning simply for being reopened.
 *
 * <p><b>Read from the employment PERIOD covering the work date</b>, not from the
 * employee row. An employee can leave and return, and
 * {@code employees.employment_start_date} mirrors only the LATEST spell — so
 * asking it about a date inside an earlier spell would answer about the wrong
 * one, and worse, would hand a returning employee a fresh probation that the
 * rehire rule says they do not serve.
 */
@Component
@RequiredArgsConstructor
public class ProbationPolicy {

    private final EmploymentPeriodService employmentPeriodService;

    /**
     * Probation runs from the period's {@code started_on} to its
     * {@code probation_end_date}, <b>both inclusive</b>, where the end is the
     * generated {@code started_on + norm_grace_days} of that same period.
     *
     * <p><b>Zero grace days is no probation, not a one-day probation.</b> With
     * {@code norm_grace_days = 0} the generated end equals the start, so the
     * arithmetic alone would put the first day inside the period. A returning
     * employee is given zero precisely to say "no probation this time", and
     * having that cost them the bonus on their first day back would be the
     * opposite of what it means.
     *
     * <p>An employee with no period covering the date — not employed then, or
     * data that has not been backfilled — is not on probation: this decides
     * whether to WITHHOLD a bonus, so missing data must fall to paying it rather
     * than to silently not.
     */
    @Transactional(readOnly = true)
    public boolean isOnProbation(Long employeeId, LocalDate workDate) {
        if (employeeId == null || workDate == null) {
            return false;
        }
        // No period covering the date means the employee was not employed then —
        // not on probation, and nothing to withhold.
        return employmentPeriodService.periodOn(employeeId, workDate)
                .filter(p -> p.getNormGraceDays() != null && p.getNormGraceDays() > 0)
                .filter(p -> p.getStartedOn() != null && p.getProbationEndDate() != null)
                .map(p -> withinProbation(p, workDate))
                .orElse(false);
    }

    private boolean withinProbation(EmployeeEmploymentPeriod period, LocalDate workDate) {
        return !workDate.isBefore(period.getStartedOn())
                && !workDate.isAfter(period.getProbationEndDate());
    }
}
