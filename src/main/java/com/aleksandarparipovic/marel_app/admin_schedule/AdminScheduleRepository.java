package com.aleksandarparipovic.marel_app.admin_schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AdminScheduleRepository extends JpaRepository<AdminScheduleEntry, Long> {

    /**
     * The grid's read: every live entry in the window, with the person and the
     * shift fetched along — the screen shows both on every cell, so lazy rows
     * would be one query per cell.
     */
    @Query("""
            SELECT e FROM AdminScheduleEntry e
            JOIN FETCH e.user
            JOIN FETCH e.shift
            WHERE e.archivedAt IS NULL
              AND e.workDate >= :from
              AND e.workDate <= :to
            ORDER BY e.workDate ASC, e.user.id ASC, e.startTime ASC NULLS FIRST
            """)
    List<AdminScheduleEntry> findLiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The duplicate check's read: live rows of the same person, day and shift. */
    @Query("""
            SELECT e FROM AdminScheduleEntry e
            WHERE e.archivedAt IS NULL
              AND e.user.id = :userId
              AND e.workDate = :date
              AND e.shift.id = :shiftId
            """)
    List<AdminScheduleEntry> findLiveFor(@Param("userId") Long userId,
                                         @Param("date") LocalDate date,
                                         @Param("shiftId") Long shiftId);
}
