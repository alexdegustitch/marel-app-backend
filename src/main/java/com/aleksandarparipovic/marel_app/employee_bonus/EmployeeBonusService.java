package com.aleksandarparipovic.marel_app.employee_bonus;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_bonus.dto.EmployeeBonusDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.recalc_queue.AffectedMonthsRecalculator;

@Service
@RequiredArgsConstructor
public class EmployeeBonusService {

    private final EmployeeBonusRepository repository;
    private final EmployeeBonusMapper mapper;
    private final AffectedMonthsRecalculator recalculator;

    public List<EmployeeBonusDto> search(Long employeeId, Boolean active) {
        Specification<EmployeeBonus> spec = Specification.where(
                EmployeeBonusSpecifications.hasEmployee(employeeId)
        ).and(EmployeeBonusSpecifications.isActive(active));

        return repository.findAll(spec)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public void assignBonus(Employee employee, BonusCategory category, User changedBy) {

        LocalDate today = LocalDate.now();

        // Close existing bonus
        repository.findByEmployeeIdAndEndDateIsNull(employee.getId())
                .ifPresent(existing -> {
                    existing.setEndDate(today.minusDays(1));
                    repository.save(existing);
                });

        // Create new record
        EmployeeBonus newBonus = EmployeeBonus.builder()
                .employee(employee)
                .bonusCategory(category)
                .startDate(today)
                .changedBy(changedBy)
                .build();

        repository.save(newBonus);
    }


    /**
     * Move an employee to a different bonus category for a DATED range, and put
     * the affected payroll months back through the calculator.
     *
     * <p>THE SPLIT. When the new range ends inside an existing open spell, the
     * old category has to come back afterwards — the owner's example: old until
     * 7 July, new 7 July to 2 August, then old again from 3 August. So the
     * covering spell is closed before the new one, and a THIRD spell re-opens it
     * after. Without that the employee would silently have no bonus category
     * from the end date onwards.
     *
     * <p>Three rows, not an UPDATE of one: ex_employees_bonus_history_no_overlap
     * refuses an insert into the middle of a live spell, and rewriting the
     * existing row would erase what the employee was actually paid before.
     *
     * <p>Locked payroll is never recalculated and the change is accepted anyway —
     * the returned result names the months that were left alone.
     */
    @Transactional
    public AffectedMonthsRecalculator.Result changeBonus(Employee employee,
                                                        BonusCategory category,
                                                        LocalDate validFrom,
                                                        LocalDate validTo,
                                                        User changedBy) {
        if (validFrom == null) {
            throw new IllegalArgumentException("Datum od kada važi bonus kategorija je obavezan.");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("Datum \"do\" ne može biti pre datuma \"od\".");
        }

        EmployeeBonus covering = repository.findByEmployeeIdAndEndDateIsNull(employee.getId()).orElse(null);
        BonusCategory previousCategory = covering != null ? covering.getBonusCategory() : null;

        if (covering != null) {
            if (covering.getStartDate().isAfter(validFrom)) {
                throw new IllegalArgumentException(
                        "Nova kategorija mora da počne posle početka trenutne ("
                                + covering.getStartDate() + ").");
            }
            covering.setEndDate(validFrom.minusDays(1));
            covering.setChangedBy(changedBy);
            repository.saveAndFlush(covering);
        }

        repository.saveAndFlush(EmployeeBonus.builder()
                .employee(employee)
                .bonusCategory(category)
                .startDate(validFrom)
                .endDate(validTo)
                .changedBy(changedBy)
                .build());

        // The old category resumes the day after a CLOSED range. Only when there
        // was one to resume — a first assignment has nothing to fall back to.
        if (validTo != null && previousCategory != null) {
            repository.saveAndFlush(EmployeeBonus.builder()
                    .employee(employee)
                    .bonusCategory(previousCategory)
                    .startDate(validTo.plusDays(1))
                    .endDate(null)
                    .changedBy(changedBy)
                    .build());
        }

        // Everything from validFrom onwards is affected, even past validTo: the
        // resumed spell changes those months too.
        return recalculator.recalculate(employee, validFrom, null,
                "Bonus kategorija promenjena od " + validFrom);
    }

    @Transactional
    public void removeBonus(User employee, User changedBy) {
        repository.findByEmployeeIdAndEndDateIsNull(employee.getId())
                .ifPresent(active -> {
                    active.setEndDate(LocalDate.now());
                    active.setChangedBy(changedBy);
                    repository.save(active);
                });
    }
}
