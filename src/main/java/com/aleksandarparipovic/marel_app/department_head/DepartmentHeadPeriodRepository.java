package com.aleksandarparipovic.marel_app.department_head;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DepartmentHeadPeriodRepository extends JpaRepository<DepartmentHeadPeriod, Long> {

    /**
     * Who headed this department on a given date.
     *
     * <p>By WORK DATE, never {@code now()} — the same rule the compensation
     * schemes follow, and for the same reason: a report about March must resolve
     * the March head, not today's.
     *
     * <p>Returns every match, because a department-wide head and a shift head can
     * both be in force. The caller decides which one it means.
     */
    @Query("""
        select p from DepartmentHeadPeriod p
        where p.department.id = :departmentId
          and p.archivedAt is null
          and p.validFrom <= :onDate
          and (p.validTo is null or p.validTo >= :onDate)
        order by p.shift.id asc nulls first
        """)
    List<DepartmentHeadPeriod> findHeadsOn(@Param("departmentId") Long departmentId,
                                           @Param("onDate") LocalDate onDate);

    /** Every spell this employee has spent heading anything, newest first. */
    @Query("""
        select p from DepartmentHeadPeriod p
        where p.employee.id = :employeeId
          and p.archivedAt is null
        order by p.validFrom desc
        """)
    List<DepartmentHeadPeriod> findForEmployee(@Param("employeeId") Long employeeId);

    /**
     * Of the given employees, which ones head something on {@code onDate}.
     *
     * <p>One query for a whole page of table rows — see DepartmentHeadEnricher
     * for why this is not a column on the projection.
     */
    @Query("""
        select distinct p.employee.id from DepartmentHeadPeriod p
        where p.employee.id in :employeeIds
          and p.archivedAt is null
          and p.validFrom <= :onDate
          and (p.validTo is null or p.validTo >= :onDate)
        """)
    List<Long> findEmployeeIdsHeadingOn(@Param("employeeIds") Collection<Long> employeeIds,
                                        @Param("onDate") LocalDate onDate);

    /** The open spell for this employee, if they are currently head of something. */
    @Query("""
        select p from DepartmentHeadPeriod p
        where p.employee.id = :employeeId
          and p.archivedAt is null
          and p.validTo is null
        """)
    Optional<DepartmentHeadPeriod> findOpenForEmployee(@Param("employeeId") Long employeeId);
}
