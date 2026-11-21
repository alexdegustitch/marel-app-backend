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
     * Every spell in force for (employee-or-default, shift, date), ALL breaks
     * at once. {@link ShiftBreakResolver} groups them by breakNo and lets the
     * employee's row win within each group — a shift can hold several breaks,
     * so one row is no longer the answer.
     */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.shift.id = :shiftId
              AND p.archivedAt IS NULL
              AND (p.employee.id = :employeeId OR p.employee IS NULL)
              AND p.validFrom <= :date
              AND (p.validTo IS NULL OR p.validTo >= :date)
            ORDER BY p.breakNo ASC
            """)
    List<EmployeeShiftBreakPeriod> findAllInForce(@Param("employeeId") Long employeeId,
                                                  @Param("shiftId") Long shiftId,
                                                  @Param("date") LocalDate date);

    /** The DEFAULT spells in force on a date, for every shift — pickers' read. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee IS NULL
              AND p.archivedAt IS NULL
              AND p.validFrom <= :date
              AND (p.validTo IS NULL OR p.validTo >= :date)
            ORDER BY p.shift.id ASC, p.breakNo ASC
            """)
    List<EmployeeShiftBreakPeriod> findDefaultsInForce(@Param("date") LocalDate date);

    /** The employee's own spells, newest first — the history a screen shows. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee.id = :employeeId
              AND p.archivedAt IS NULL
            ORDER BY p.validFrom DESC, p.breakNo ASC, p.id DESC
            """)
    List<EmployeeShiftBreakPeriod> findHistoryFor(@Param("employeeId") Long employeeId);

    /** The employee's OPEN spell for one break of one shift, if any. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee.id = :employeeId
              AND p.shift.id = :shiftId
              AND p.breakNo = :breakNo
              AND p.archivedAt IS NULL
              AND p.validTo IS NULL
            """)
    Optional<EmployeeShiftBreakPeriod> findOpenFor(@Param("employeeId") Long employeeId,
                                                   @Param("shiftId") Long shiftId,
                                                   @Param("breakNo") Integer breakNo);

    /**
     * The employee's own spells for one break of one shift that would overlap
     * [from, to] (to null = open-ended). The friendly pre-check;
     * ex_esbp_no_overlap in the database is the guarantee.
     */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee.id = :employeeId
              AND p.shift.id = :shiftId
              AND p.breakNo = :breakNo
              AND p.archivedAt IS NULL
              AND p.id <> :excludeId
              AND p.validFrom <= COALESCE(:to, p.validFrom)
              AND (p.validTo IS NULL OR p.validTo >= :from)
            """)
    List<EmployeeShiftBreakPeriod> findOwnOverlapping(@Param("employeeId") Long employeeId,
                                                      @Param("shiftId") Long shiftId,
                                                      @Param("breakNo") Integer breakNo,
                                                      @Param("from") LocalDate from,
                                                      @Param("to") LocalDate to,
                                                      @Param("excludeId") Long excludeId);

    /** The shift's default spells, newest first — the šifarnik's history. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee IS NULL
              AND p.shift.id = :shiftId
              AND p.archivedAt IS NULL
            ORDER BY p.validFrom DESC, p.breakNo ASC, p.id DESC
            """)
    List<EmployeeShiftBreakPeriod> findDefaultHistoryFor(@Param("shiftId") Long shiftId);

    /** The shift's OPEN default spell for one break, if any. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee IS NULL
              AND p.shift.id = :shiftId
              AND p.breakNo = :breakNo
              AND p.archivedAt IS NULL
              AND p.validTo IS NULL
            """)
    Optional<EmployeeShiftBreakPeriod> findOpenDefaultFor(@Param("shiftId") Long shiftId,
                                                          @Param("breakNo") Integer breakNo);

    /** Every OPEN default spell of one shift, by break. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee IS NULL
              AND p.shift.id = :shiftId
              AND p.archivedAt IS NULL
              AND p.validTo IS NULL
            ORDER BY p.breakNo ASC
            """)
    List<EmployeeShiftBreakPeriod> findOpenDefaultsFor(@Param("shiftId") Long shiftId);

    /** Every OPEN default spell of every shift — the šifarnik's list view. */
    @Query("""
            SELECT p FROM EmployeeShiftBreakPeriod p
            WHERE p.employee IS NULL
              AND p.archivedAt IS NULL
              AND p.validTo IS NULL
            ORDER BY p.shift.id ASC, p.breakNo ASC
            """)
    List<EmployeeShiftBreakPeriod> findAllOpenDefaults();
}
