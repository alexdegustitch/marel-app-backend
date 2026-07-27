package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface EmployeeCompensationSchemeHistoryRepository
        extends JpaRepository<EmployeeCompensationSchemeHistory, Long> {

    /**
     * Every non-archived period covering {@code date} for one employee.
     *
     * <p>Returns a list rather than an {@code Optional} on purpose: "more than one
     * scheme applies" is a data-integrity failure the caller must report as a
     * clear business error, not something to silently resolve by picking the
     * first row. The exclusion constraint should make it impossible; this query
     * still refuses to hide it if it ever happens.
     */
    @Query("""
            SELECT h FROM EmployeeCompensationSchemeHistory h
            JOIN FETCH h.compensationScheme
            WHERE h.employee.id = :employeeId
              AND h.archivedAt IS NULL
              AND h.validFrom <= :date
              AND (h.validUntil IS NULL OR h.validUntil >= :date)
            """)
    List<EmployeeCompensationSchemeHistory> findActiveAt(
            @Param("employeeId") Long employeeId,
            @Param("date") LocalDate date);

    /**
     * The same lookup for many employees at once — the payroll and recalc paths
     * resolve whole batches and must not issue one query per line.
     */
    @Query("""
            SELECT h FROM EmployeeCompensationSchemeHistory h
            JOIN FETCH h.compensationScheme
            WHERE h.employee.id IN :employeeIds
              AND h.archivedAt IS NULL
              AND h.validFrom <= :date
              AND (h.validUntil IS NULL OR h.validUntil >= :date)
            """)
    List<EmployeeCompensationSchemeHistory> findActiveAtForEmployees(
            @Param("employeeIds") Collection<Long> employeeIds,
            @Param("date") LocalDate date);

    @Query("""
            SELECT h FROM EmployeeCompensationSchemeHistory h
            JOIN FETCH h.compensationScheme
            WHERE h.employee.id = :employeeId
              AND h.archivedAt IS NULL
            ORDER BY h.validFrom ASC
            """)
    List<EmployeeCompensationSchemeHistory> findHistoryFor(@Param("employeeId") Long employeeId);

    /**
     * Every period of these employees that overlaps {@code [from, to]}.
     *
     * <p>A payroll month is a range, and an employee can change scheme inside
     * one, so the payroll layer needs every scheme that governed any part of the
     * month — not the one in force on a single date.
     */
    @Query("""
            SELECT h FROM EmployeeCompensationSchemeHistory h
            JOIN FETCH h.compensationScheme
            WHERE h.employee.id IN :employeeIds
              AND h.archivedAt IS NULL
              AND h.validFrom <= :to
              AND (h.validUntil IS NULL OR h.validUntil >= :from)
            """)
    List<EmployeeCompensationSchemeHistory> findOverlapping(
            @Param("employeeIds") Collection<Long> employeeIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * All of one employee's non-archived periods, row-locked.
     *
     * <p>Used by the "change scheme" transaction. The exclusion constraint is the
     * real guarantee; this lock exists so two concurrent changes for the same
     * employee serialise into a clean sequential outcome instead of one of them
     * failing on a constraint violation the user cannot act on.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT h FROM EmployeeCompensationSchemeHistory h
            WHERE h.employee.id = :employeeId
              AND h.archivedAt IS NULL
            ORDER BY h.validFrom ASC
            """)
    List<EmployeeCompensationSchemeHistory> lockHistoryFor(@Param("employeeId") Long employeeId);
}
