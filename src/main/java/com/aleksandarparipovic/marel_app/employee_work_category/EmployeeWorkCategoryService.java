package com.aleksandarparipovic.marel_app.employee_work_category;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Moving an employee between default work categories.
 *
 * <p>NOTHING HERE RECALCULATES ANYTHING, and that is the point. The category on
 * this period only pre-fills what a supervisor is offered when logging work; the
 * calculation reads the category recorded ON THE WORK LOG. The owner confirmed
 * it explicitly. If that ever stops being true, this service is where the
 * recalculation would have to be enqueued — and this comment is wrong.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeWorkCategoryService {

    private final EmployeeWorkCategoryPeriodRepository repository;
    private final EmployeeRepository employeeRepository;
    private final WorkCodeCategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<EmployeeWorkCategoryPeriodDto> history(Long employeeId) {
        return repository.findHistoryFor(employeeId).stream()
                .map(EmployeeWorkCategoryPeriodDto::from)
                .toList();
    }

    /**
     * Close the open spell and start a new one.
     *
     * <p>Transition semantics match the compensation scheme: the previous spell
     * is CLOSED the day before the new one begins, never edited to point at a
     * different category. Rewriting it would erase what the employee actually
     * worked in before, which is the whole reason this table exists.
     */
    @Transactional
    public EmployeeWorkCategoryPeriodDto change(Long employeeId, ChangeWorkCategoryRequest request) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Zaposleni ne postoji: " + employeeId));

        WorkCodeCategory category = categoryRepository.findById(request.getWorkCodeCategoryId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kategorija rada ne postoji: " + request.getWorkCodeCategoryId()));

        // Only a category somebody can actually be assigned to. The form offers
        // no others, so this refuses a hand-written request rather than a click.
        if (!Boolean.TRUE.equals(category.getBaseOperation())) {
            throw new IllegalArgumentException(
                    "Kategorija \"" + category.getCategoryNo()
                            + "\" ne može biti podrazumevana — nije osnovna operacija.");
        }

        if (request.getValidTo() != null && request.getValidTo().isBefore(request.getValidFrom())) {
            throw new IllegalArgumentException("Datum \"do\" ne može biti pre datuma \"od\".");
        }

        repository.findOpenFor(employeeId).ifPresent(open -> {
            if (!request.getValidFrom().isAfter(open.getValidFrom())) {
                throw new IllegalArgumentException(
                        "Nova kategorija mora da počne posle početka trenutne ("
                                + open.getValidFrom() + ").");
            }
            // Inclusive end, so the day BEFORE the new spell begins.
            open.setValidTo(request.getValidFrom().minusDays(1));
            repository.saveAndFlush(open);
        });

        EmployeeWorkCategoryPeriod created = repository.saveAndFlush(EmployeeWorkCategoryPeriod.builder()
                .employee(employee)
                .workCodeCategory(category)
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .note(request.getNote())
                .build());

        log.debug("Employee {} moved to work category {} from {}",
                employeeId, category.getCategoryNo(), request.getValidFrom());

        return EmployeeWorkCategoryPeriodDto.from(created);
    }

    /**
     * Open the FIRST spell, at creation.
     *
     * <p>Separate from {@link #change}: there is no previous spell to close, and
     * the start date is the employee's own rather than one somebody typed. Opens
     * a period rather than writing employees.default_work_category_id, which is
     * only a trigger-maintained mirror of this table.
     */
    @Transactional
    public void openFirstPeriod(Employee employee, Long workCodeCategoryId, LocalDate from) {
        if (workCodeCategoryId == null) {
            return;
        }
        WorkCodeCategory category = categoryRepository.findById(workCodeCategoryId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kategorija rada ne postoji: " + workCodeCategoryId));

        if (!Boolean.TRUE.equals(category.getBaseOperation())) {
            throw new IllegalArgumentException(
                    "Kategorija \"" + category.getCategoryNo()
                            + "\" ne može biti podrazumevana — nije osnovna operacija.");
        }

        repository.saveAndFlush(EmployeeWorkCategoryPeriod.builder()
                .employee(employee)
                .workCodeCategory(category)
                .validFrom(from != null ? from : LocalDate.now())
                .note("Opened with the employee.")
                .build());
    }

    /** Close the open spell without starting another one. */
    @Transactional
    public void close(Long employeeId, LocalDate endedOn) {
        repository.findOpenFor(employeeId).ifPresent(open -> {
            if (endedOn.isBefore(open.getValidFrom())) {
                throw new IllegalArgumentException(
                        "Kraj ne može biti pre početka (" + open.getValidFrom() + ").");
            }
            open.setValidTo(endedOn);
            repository.saveAndFlush(open);
        });
    }
}
