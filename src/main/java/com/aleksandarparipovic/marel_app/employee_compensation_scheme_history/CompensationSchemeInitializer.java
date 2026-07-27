package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
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
     */
    public void assignInitialScheme(Employee employee) {
        if (!historyRepository.findHistoryFor(employee.getId()).isEmpty()) {
            return;
        }

        CompensationScheme standard = schemeRepository.findByCode(CompensationSchemeCodes.STANDARD)
                .orElseThrow(() -> new IllegalStateException(
                        "The STANDARD compensation scheme is missing; run 2026-07-27-01"));

        LocalDate from = employee.getEmploymentStartDate() != null
                ? employee.getEmploymentStartDate()
                : LocalDate.now();

        historyRepository.save(EmployeeCompensationSchemeHistory.builder()
                .employee(employee)
                .compensationScheme(standard)
                .validFrom(from)
                .validUntil(null)
                .note("Opening period created with the employee.")
                .build());

        log.debug("Employee {} opened on the STANDARD compensation scheme from {}", employee.getId(), from);
    }
}
