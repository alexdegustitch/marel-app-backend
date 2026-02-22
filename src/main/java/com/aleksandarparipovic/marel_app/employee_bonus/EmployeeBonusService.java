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

@Service
@RequiredArgsConstructor
public class EmployeeBonusService {

    private final EmployeeBonusRepository repository;
    private final EmployeeBonusMapper mapper;

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
