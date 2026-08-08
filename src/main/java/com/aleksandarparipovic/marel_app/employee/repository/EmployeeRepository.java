package com.aleksandarparipovic.marel_app.employee.repository;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository
        extends JpaRepository<Employee, Long>,
        JpaSpecificationExecutor<Employee>,
        EmployeeRepositoryCustom {

    boolean existsByEmployeeNo(String employeeNo);

    List<Employee> findAllByActiveTrueAndArchivedAtIsNull();

    Page<EmployeeWithBonusView> findAllProjectedBy(
            Specification<Employee> spec,
            Pageable pageable
    );
    @Query("""
    select
      e.id as employeeId,
      e.employeeNo as employeeNo,
      e.firstName as firstName,
      e.lastName as lastName,
      e.fullName as fullName,
      d.name as departmentName,
      d.id as departmentId,
      e.employmentStartDate as employmentStartDate,
      e.probationEndDate as probationEndDate,
      e.notes as notes,
      e.transportAllowanceRsd as transportAllowanceRsd,
      e.transportAllowanceMode as transportAllowanceMode,
      bc.categoryNo as categoryNo,
      bc.id as categoryId,
      bc.categoryName as categoryName,
      bc.bonusAmount as bonusAmount,
      eb.startDate as bonusStart,
      cs.code as schemeCode,
      cs.name as schemeName,
      cs.allowsPerformanceBonus as allowsPerformanceBonus,
      e.mobilePhone as mobilePhone,
      e.email as email,
      e.hourlyRate as hourlyRate,
      wcc.id as defaultWorkCategoryId,
      wcc.categoryName as defaultWorkCategoryName,
      e.preferredLocale as preferredLocale
    
    from Employee e
    join e.department d
    
    left join EmployeeBonus eb
      on eb.employee = e and eb.endDate is null
    
    left join eb.bonusCategory bc
      on bc.archivedAt is null
     and current_date between bc.validFrom and coalesce(bc.validUntil, '9999-12-31')
    
    left join e.compensationSchemePeriods csp
      on csp.validUntil is null and csp.archivedAt is null
    left join csp.compensationScheme cs

    left join e.defaultWorkCategory wcc
    
    where e.archivedAt is null
    order by e.id asc
    """)
    Page<EmployeeWithBonusView> findEmployeesWithCurrentBonus(Pageable pageable);

    @Query("""
    select
      e.id as employeeId,
      e.employeeNo as employeeNo,
      e.firstName as firstName,
      e.lastName as lastName,
      e.fullName as fullName,
      d.name as departmentName,
      d.id as departmentId,
      e.employmentStartDate as employmentStartDate,
      e.probationEndDate as probationEndDate,
      e.notes as notes,
      e.transportAllowanceRsd as transportAllowanceRsd,
      e.transportAllowanceMode as transportAllowanceMode,
      bc.categoryNo as categoryNo,
      bc.id as categoryId,
      bc.categoryName as categoryName,
      bc.bonusAmount as bonusAmount,
      eb.startDate as bonusStart,
      cs.code as schemeCode,
      cs.name as schemeName,
      cs.allowsPerformanceBonus as allowsPerformanceBonus,
      e.mobilePhone as mobilePhone,
      e.email as email,
      e.hourlyRate as hourlyRate,
      wcc.id as defaultWorkCategoryId,
      wcc.categoryName as defaultWorkCategoryName,
      e.preferredLocale as preferredLocale
    from Employee e
    join e.department d
    left join EmployeeBonus eb
      on eb.employee = e and eb.endDate is null
    left join eb.bonusCategory bc
      on bc.archivedAt is null
     and current_date between bc.validFrom and coalesce(bc.validUntil, '9999-12-31')
    left join e.compensationSchemePeriods csp
      on csp.validUntil is null and csp.archivedAt is null
    left join csp.compensationScheme cs

    left join e.defaultWorkCategory wcc
    where e.archivedAt is null
      and e.id = :id
    """)
    Optional<EmployeeWithBonusView> findEmployeeWithBonusById(@Param("id") Long id);

    @Query("SELECT e FROM Employee e WHERE e.active = true AND e.archivedAt IS NULL AND e.employmentEndDate IS NOT NULL AND e.employmentEndDate <= :today")
    List<Employee> findActiveEmployeesWithExpiredEndDate(@Param("today") LocalDate today);

    @Query("SELECT e FROM Employee e LEFT JOIN FETCH e.department LEFT JOIN FETCH e.employeeBonuses b LEFT JOIN FETCH b.bonusCategory LEFT JOIN FETCH e.defaultWorkCategory WHERE e.id = :id")
    Optional<Employee> findByIdWithDetails(@Param("id") Long id);

}
