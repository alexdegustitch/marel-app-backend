package com.aleksandarparipovic.marel_app.employee.repository;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRepository
        extends JpaRepository<Employee, Long>,
        JpaSpecificationExecutor<Employee>,
        EmployeeRepositoryCustom {

    boolean existsByEmployeeNo(String employeeNo);

    Page<EmployeeWithBonusView> findAllProjectedBy(
            Specification<Employee> spec,
            Pageable pageable
    );
    @Query("""
    select
      e.id as employeeId,
      e.employeeNo as employeeNo,
      e.fullName as fullName,
      d.name as departmentName,
      d.id as departmentId,
      e.employmentStartDate as employmentStartDate,
      e.probationEndDate as probationEndDate,
      e.notes as notes,
      e.transportAllowanceRsd as transportAllowanceRsd,
      bc.categoryNo as categoryNo,
      bc.id as categoryId,
      bc.categoryName as categoryName,
      bc.bonusAmount as bonusAmount,
      eb.startDate as bonusStart,
      e.foreigner as foreigner
    
    from Employee e
    join e.department d
    
    left join EmployeeBonus eb
      on eb.employee = e and eb.endDate is null
    
    left join eb.bonusCategory bc
      on bc.archivedAt is null
     and current_date between bc.validFrom and coalesce(bc.validUntil, '9999-12-31')
    
    where e.archivedAt is null
    order by e.id asc
    """)
    Page<EmployeeWithBonusView> findEmployeesWithCurrentBonus(Pageable pageable);

    @Query("""
    select
      e.id as employeeId,
      e.employeeNo as employeeNo,
      e.fullName as fullName,
      d.name as departmentName,
      d.id as departmentId,
      e.employmentStartDate as employmentStartDate,
      e.probationEndDate as probationEndDate,
      e.notes as notes,
      e.transportAllowanceRsd as transportAllowanceRsd,
      bc.categoryNo as categoryNo,
      bc.id as categoryId,
      bc.categoryName as categoryName,
      bc.bonusAmount as bonusAmount,
      eb.startDate as bonusStart,
      e.foreigner as foreigner
    from Employee e
    join e.department d
    left join EmployeeBonus eb
      on eb.employee = e and eb.endDate is null
    left join eb.bonusCategory bc
      on bc.archivedAt is null
     and current_date between bc.validFrom and coalesce(bc.validUntil, '9999-12-31')
    where e.archivedAt is null
      and e.id = :id
    """)
    Optional<EmployeeWithBonusView> findEmployeeWithBonusById(@Param("id") Long id);

}
