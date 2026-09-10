package com.aleksandarparipovic.marel_app.employee_leave;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EmployeeLeavePeriodRepository extends JpaRepository<EmployeeLeavePeriod, Long> {

    /** Live periods of one employee that intersect [from, to]. */
    @Query("""
        SELECT p FROM EmployeeLeavePeriod p
        JOIN FETCH p.workCodeCategory
        WHERE p.employee.id = :employeeId
          AND p.archivedAt IS NULL
          AND p.dateFrom <= :to
          AND p.dateTo >= :from
        ORDER BY p.dateFrom
        """)
    List<EmployeeLeavePeriod> findIntersecting(@Param("employeeId") Long employeeId,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /** Live periods of ANY employee that intersect [from, to] — the karton-creation sweep. */
    @Query("""
        SELECT p FROM EmployeeLeavePeriod p
        JOIN FETCH p.workCodeCategory
        JOIN FETCH p.employee
        WHERE p.archivedAt IS NULL
          AND p.dateFrom <= :to
          AND p.dateTo >= :from
        ORDER BY p.employee.id, p.dateFrom
        """)
    List<EmployeeLeavePeriod> findAllIntersecting(@Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);
}
