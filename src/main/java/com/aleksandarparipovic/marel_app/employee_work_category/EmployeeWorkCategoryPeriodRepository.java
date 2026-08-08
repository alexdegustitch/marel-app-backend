package com.aleksandarparipovic.marel_app.employee_work_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeWorkCategoryPeriodRepository extends JpaRepository<EmployeeWorkCategoryPeriod, Long> {

    /** Every spell for this employee, newest first. */
    @Query("""
        select p from EmployeeWorkCategoryPeriod p
        where p.employee.id = :employeeId and p.archivedAt is null
        order by p.validFrom desc, p.id desc
        """)
    List<EmployeeWorkCategoryPeriod> findHistoryFor(@Param("employeeId") Long employeeId);

    /** The open spell, if the employee currently has one. */
    @Query("""
        select p from EmployeeWorkCategoryPeriod p
        where p.employee.id = :employeeId and p.archivedAt is null and p.validTo is null
        """)
    Optional<EmployeeWorkCategoryPeriod> findOpenFor(@Param("employeeId") Long employeeId);
}
