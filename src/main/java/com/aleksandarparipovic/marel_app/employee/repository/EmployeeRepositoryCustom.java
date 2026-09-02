package com.aleksandarparipovic.marel_app.employee.repository;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDirectorySummary;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

public interface EmployeeRepositoryCustom {
    Page<EmployeeWithBonusView> searchWithBonus(
            Specification<Employee> spec,
            Pageable pageable
    );
    <T> Page<T> searchWithProjection(Specification<Employee> spec, Pageable pageable, Class<T> projectionType);

    /**
     * The directory's headline figures for the employees {@code spec} matches:
     * total, per open scheme, and on probation as of {@code today}.
     */
    EmployeeDirectorySummary directorySummary(Specification<Employee> spec, java.time.LocalDate today);

}
