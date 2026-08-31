package com.aleksandarparipovic.marel_app.absence_record;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AbsenceRecordRepository extends JpaRepository<AbsenceRecord, Long> {

    /** Every live absence on one shift, for the day's totals. */
    @Query("""
        select a from AbsenceRecord a
        join fetch a.workCodeCategory
        where a.workShift.id = :workShiftId
          and a.isActive = true
          and a.archivedAt is null
        order by a.startAt asc, a.id asc
        """)
    List<AbsenceRecord> findActiveForShift(@Param("workShiftId") Long workShiftId);

    /**
     * One employee's live absences over a period, in the order the allocation
     * spends the bank on them: by day, then by start, then by id.
     *
     * <p>The date lives on the shift, not here — an absence is always inside one.
     * Ordering is total and deterministic on purpose; see
     * {@code OvertimeRecordRepository#findForEmployeeBetween}.
     */
    @Query("""
        select a from AbsenceRecord a
        join fetch a.workShift ws
        join fetch a.workCodeCategory
        where a.employee.id = :employeeId
          and ws.workDate between :from and :to
          and a.isActive = true
          and a.archivedAt is null
        order by ws.workDate asc, a.startAt asc, a.id asc
        """)
    List<AbsenceRecord> findActiveForEmployeeBetween(@Param("employeeId") Long employeeId,
                                                     @Param("from") LocalDate from,
                                                     @Param("to") LocalDate to);

    /** Does this shift carry any live absence at all — used by the delete guard. */
    boolean existsByWorkShift_IdAndIsActiveTrueAndArchivedAtIsNull(Long workShiftId);
}
