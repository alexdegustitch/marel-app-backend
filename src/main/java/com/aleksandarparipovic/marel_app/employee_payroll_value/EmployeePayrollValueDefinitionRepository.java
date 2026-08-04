package com.aleksandarparipovic.marel_app.employee_payroll_value;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeePayrollValueDefinitionRepository
        extends JpaRepository<EmployeePayrollValueDefinition, Long> {

    Optional<EmployeePayrollValueDefinition> findByCode(String code);

    List<EmployeePayrollValueDefinition> findByIsActiveTrueAndArchivedAtIsNullOrderByCodeAsc();
}
