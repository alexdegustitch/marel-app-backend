package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Gives a newly created employee their opening compensation-scheme period.
 *
 * <p>Without one, the first work log recorded for them is rejected and their
 * first recalculation job fails — the resolver refuses to guess, by design. This
 * closes that gap at the only point where an employee comes into existence.
 *
 * <p>Always {@code STANDARD}, and deliberately never inferred from
 * {@code is_foreigner}. The restricted policy is a payroll decision an
 * administrator makes explicitly, with an effective date; deriving it from a
 * personnel attribute is exactly the conflation this feature exists to undo.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompensationSchemeInitializer {

    private final CompensationSchemeRepository schemeRepository;
    private final EmployeeCompensationSchemeHistoryRepository historyRepository;

    /**
     * Open a {@code STANDARD} period from the employee's start date.
     *
     * <p>Idempotent: an employee who already has any period is left alone, so
     * this is safe to call from more than one creation path.
     *
     * <p>For creation paths that do not ask which scheme applies. The employee
     * screen now does ask, and calls {@link #assignInitialScheme(Employee, Long)}
     * instead — a chosen scheme beats a defaulted one, because the scheme decides
     * which categories are usable and whether a bonus is earned at all.
     */
    public void assignInitialScheme(Employee employee) {
        CompensationScheme standard = schemeRepository.findByCode(CompensationSchemeCodes.STANDARD)
                .orElseThrow(() -> new IllegalStateException(
                        "The STANDARD compensation scheme is missing; run 2026-07-27-01"));
        openPeriod(employee, standard, "Opening period created with the employee.");
    }

    /**
     * Open the CHOSEN scheme from the employee's start date.
     *
     * <p>The scheme must exist, be active and not archived. A request naming an
     * archived or inactive scheme is rejected rather than quietly falling back to
     * STANDARD: the business rules are explicit that a missing or unusable scheme
     * is a misconfiguration, and hiding it behind a plausible default is worse
     * than failing.
     */
    public void assignInitialScheme(Employee employee, Long compensationSchemeId) {
        if (compensationSchemeId == null) {
            assignInitialScheme(employee);
            return;
        }

        CompensationScheme chosen = schemeRepository.findById(compensationSchemeId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Način obračuna ne postoji: " + compensationSchemeId));

        if (!chosen.isUsable()) {
            throw new IllegalStateException(
                    "Način obračuna \"" + chosen.getName() + "\" nije aktivan i ne može se dodeliti.");
        }

        openPeriod(employee, chosen, "Opening period created with the employee, scheme chosen by the administrator.");
    }

    private void openPeriod(Employee employee, CompensationScheme scheme, String note) {
        if (!historyRepository.findHistoryFor(employee.getId()).isEmpty()) {
            return;
        }

        LocalDate from = employee.getEmploymentStartDate() != null
                ? employee.getEmploymentStartDate()
                : LocalDate.now();

        historyRepository.save(EmployeeCompensationSchemeHistory.builder()
                .employee(employee)
                .compensationScheme(scheme)
                .validFrom(from)
                .validUntil(null)
                .note(note)
                .build());

        log.debug("Employee {} opened on the {} compensation scheme from {}",
                employee.getId(), scheme.getCode(), from);
    }
}
