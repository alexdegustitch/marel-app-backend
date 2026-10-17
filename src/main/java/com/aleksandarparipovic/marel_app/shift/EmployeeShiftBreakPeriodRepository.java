package com.aleksandarparipovic.marel_app.shift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeShiftBreakPeriodRepository extends JpaRepository<EmployeeShiftBreakPeriod, Long> {

    /**
     * The row in force for (employee, shift, date): the employee's own spell if
     * one covers the date, the default spell otherwise. One query — the
     * employee row sorts first and LIMIT keeps it. Same shape as
     * {@link EmployeeShiftTimePeriodRepository#findInForce}.
     */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.shift.id = :shiftId
              AND p.archivedAt IS NULL
              AND (p.employee.id = :employeeId OR p.employee IS NULL)
              AND p.validFrom <= :date
              AND (p.validTo IS NULL OR p.validTo >= :date)
            ORDER BY p.employee.id ASC NULLS LAST
            LIMIT 1
            """)
    Optional<EmployeeShiftBreakPeriod> findInForce(@Param("employeeId") Long employeeId,
                                                   @Param("shiftId") Long shiftId,
                                                   @Param("date") LocalDate date);

    /** The employee's own spells, newest first — the history a screen shows. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee.id = :employeeId
              AND p.archivedAt IS NULL
            ORDER BY p.validFrom DESC, p.id DESC
            """)
    List<EmployeeShiftBreakPeriod> findHistoryFor(@Param("employeeId") Long employeeId);

    /** The employee's OPEN spell for one shift (validTo null), if any. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee.id = :employeeId
              AND p.shift.id = :shiftId
              AND p.archivedAt IS NULL
              AND p.validTo IS NULL
            """)
    Optional<EmployeeShiftBreakPeriod> findOpenFor(@Param("employeeId") Long employeeId,
                                                   @Param("shiftId") Long shiftId);

    /**
     * The employee's own spells for one shift that would overlap [from, to]
     * (to null = open-ended). The friendly pre-check; ex_esbp_no_overlap in the
     * database is the guarantee.
     */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee.id = :employeeId
              AND p.shift.id = :shiftId
              AND p.archivedAt IS NULL
              AND p.id <> :excludeId
              AND p.validFrom <= COALESCE(:to, p.validFrom)
              AND (p.validTo IS NULL OR p.validTo >= :from)
            """)
    List<EmployeeShiftBreakPeriod> findOwnOverlapping(@Param("employeeId") Long employeeId,
                                                      @Param("shiftId") Long shiftId,
                                                      @Param("from") LocalDate from,
                                                      @Param("to") LocalDate to,
                                                      @Param("excludeId") Long excludeId);

    /** The shift's default spells, newest first — the šifarnik's history. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee IS NULL
              AND p.shift.id = :shiftId
              AND p.archivedAt IS NULL
            ORDER BY p.validFrom DESC, p.id DESC
            """)
    List<EmployeeShiftBreakPeriod> findDefaultHistoryFor(@Param("shiftId") Long shiftId);

    /** The shift's OPEN default spell (employee null, validTo null), if any. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee IS NULL
              AND p.shift.id = :shiftId
              AND p.archivedAt IS NULL
              AND p.validTo IS NULL
            """)
    Optional<EmployeeShiftBreakPeriod> findOpenDefaultFor(@Param("shiftId") Long shiftId);
}
