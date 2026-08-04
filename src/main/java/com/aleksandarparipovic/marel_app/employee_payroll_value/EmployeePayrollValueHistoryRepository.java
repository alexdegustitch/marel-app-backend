package com.aleksandarparipovic.marel_app.employee_payroll_value;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeePayrollValueHistoryRepository
        extends JpaRepository<EmployeePayrollValueHistory, Long> {

    /**
     * Every value in force for a batch of employees on one date.
     *
     * <p><b>Batched by design</b>, exactly like
     * {@code PayrollSchemeScopeService.scopesFor}: a payroll run resolves every
     * employee's values in one query rather than four per row. Both bounds are
     * inclusive, matching the column's documented meaning.
     */
    @Query("""
            SELECT h FROM EmployeePayrollValueHistory h
            JOIN FETCH h.definition d
            WHERE h.employee.id IN :employeeIds
              AND h.archivedAt IS NULL
              AND h.validFrom <= :on
              AND (h.validUntil IS NULL OR h.validUntil >= :on)
            """)
    List<EmployeePayrollValueHistory> findInForceForEmployees(
            @Param("employeeIds") Collection<Long> employeeIds,
            @Param("on") LocalDate on);

    /** The single value in force for one employee and one definition code. */
    @Query("""
            SELECT h FROM EmployeePayrollValueHistory h
            JOIN FETCH h.definition d
            WHERE h.employee.id = :employeeId
              AND d.code = :code
              AND h.archivedAt IS NULL
              AND h.validFrom <= :on
              AND (h.validUntil IS NULL OR h.validUntil >= :on)
            """)
    Optional<EmployeePayrollValueHistory> findInForce(@Param("employeeId") Long employeeId,
                                                     @Param("code") String code,
                                                     @Param("on") LocalDate on);

    /** Full history for one employee, newest first. */
    @Query("""
            SELECT h FROM EmployeePayrollValueHistory h
            JOIN FETCH h.definition d
            WHERE h.employee.id = :employeeId
              AND (:code IS NULL OR d.code = :code)
            ORDER BY d.code ASC, h.validFrom DESC
            """)
    List<EmployeePayrollValueHistory> findHistoryFor(@Param("employeeId") Long employeeId,
                                                     @Param("code") String code);

    /**
     * Locks one employee's periods for the rest of the transaction.
     *
     * <p>{@code ex_epvh_no_overlap} is the real guarantee against overlapping
     * periods; the lock exists so that two concurrent changes serialise into a
     * clean sequence instead of one dying on a constraint violation the user
     * cannot act on. Same pattern as
     * {@code EmployeeCompensationSchemeHistoryRepository.lockHistoryFor}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT h FROM EmployeePayrollValueHistory h
            WHERE h.employee.id = :employeeId
              AND h.definition.id = :definitionId
              AND h.archivedAt IS NULL
            ORDER BY h.validFrom ASC
            """)
    List<EmployeePayrollValueHistory> lockPeriodsFor(@Param("employeeId") Long employeeId,
                                                     @Param("definitionId") Long definitionId);
}
