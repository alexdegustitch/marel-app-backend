package com.aleksandarparipovic.marel_app.work_shift.repository;

import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftActivityDto;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftDetailInfo;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftDto;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkShiftRepository extends JpaRepository<WorkShift, Long>, JpaSpecificationExecutor<WorkShift> {


/*
    @Query(value = """
        SELECT
            t.id AS id,
            e.full_name AS employeeName,
            e.id AS employeeId,
            EXTRACT(MONTH FROM t.start_at)::int AS month,
            :year AS year
        FROM (
            SELECT ws.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY date_trunc('month', ws.start_at)
                       ORDER BY ws.start_at DESC
                   ) AS rn
            FROM work_shifts ws
            WHERE ws.supervisor_id = :supervisorId
              AND EXTRACT(YEAR FROM ws.start_at) = :year
        ) t
        JOIN employees e
            ON t.employee_id = e.id
        WHERE rn <= 3
        ORDER BY t.start_at DESC
        """, nativeQuery = true)
    List<WorkShiftDto> findLastThreePerMonthForSupervisor(Long supervisorId, int year);*/

    @Query(value = """
        WITH employee_activity AS (
            SELECT
                ws.employee_id,
                date_trunc('month', ws.start_at) AS month,
                MAX(ws.last_activity_at)          AS last_activity
            FROM work_shifts ws
            WHERE ws.supervisor_id = :userId
              AND ws.start_at >= make_date(CAST(:year AS int), 1, 1)
              AND ws.start_at <  make_date(CAST(:year AS int) + 1, 1, 1)
            GROUP BY ws.employee_id, date_trunc('month', ws.start_at)
        ),
        ranked AS (
            SELECT
                employee_id,
                month,
                last_activity,
                ROW_NUMBER() OVER (
                    PARTITION BY month
                    ORDER BY last_activity DESC
                ) AS rn
            FROM employee_activity
        )
        SELECT
            er.id                                   AS employeeRecordId,
            e.full_name                             AS employeeName,
            r.employee_id                           AS employeeId,
            EXTRACT(MONTH FROM r.month)::int        AS month,
            CAST(:year AS int)                      AS year,
            r.last_activity                         AS updateTime
        FROM ranked r
        JOIN employees e ON e.id = r.employee_id
        LEFT JOIN employee_records er
            ON er.employee_id = r.employee_id
           AND date_trunc('month', er.start_date) = r.month
        WHERE r.rn <= 3
        ORDER BY r.month DESC, r.last_activity DESC, e.full_name ASC
        """, nativeQuery = true)
    List<WorkShiftActivityDto> findLastThreePerMonthForSupervisor(@Param("userId") Long userId,
                                                                  @Param("year") int year);

    @Query(
            value = """
        SELECT t.employeeId,
               t.employeeNo,
               t.employeeName,
               t.employeeForeigner,
               t.updateTime,
               d.name as employeeDepartment,
               bc.category_no as employeeBonus
        FROM (
            SELECT e.id AS employeeId,
                   e.full_name AS employeeName,
                   e.employee_no as employeeNo,
                   (SELECT cs.code FROM employee_compensation_scheme_history h
                     JOIN compensation_schemes cs ON cs.id = h.compensation_scheme_id
                    WHERE h.employee_id = e.id AND h.valid_until IS NULL AND h.archived_at IS NULL
                    LIMIT 1) AS employeeSchemeCode,
                   e.department_id,
                   eb.bonus_category_id,
                   MAX(ws.last_activity_at) AS updateTime
            FROM employees e
            JOIN work_shifts ws ON ws.employee_id = e.id
            JOIN employees_bonus_history eb ON eb.employee_id = e.id
            WHERE ws.start_at >= :monthStart
              AND ws.start_at < :monthEnd
              AND eb.end_date IS NULL
              AND (
                    :search IS NULL
                    OR e.full_name ILIKE '%' || :search || '%'
                    OR e.employee_no ILIKE '%' || :search || '%'
                  )
            GROUP BY e.id, e.full_name, e.employee_no, e.department_id, eb.bonus_category_id
        ) t
        JOIN departments d ON d.id = t.department_id
        JOIN bonus_categories bc ON bc.id = t.bonus_category_id
    """,
            countQuery = """
        SELECT COUNT(DISTINCT e.id)
        FROM employees e
        JOIN work_shifts ws ON ws.employee_id = e.id
        JOIN employees_bonus_history eb ON eb.employee_id = e.id
        WHERE ws.start_at >= :monthStart
          AND ws.start_at < :monthEnd
          AND eb.end_date IS NULL
          AND (
                :search IS NULL
                OR e.full_name ILIKE '%' || :search || '%'
                OR e.employee_no ILIKE '%' || :search || '%'
              )
    """,
            nativeQuery = true
    )
    Page<WorkShiftInfo> findMonthlyShifts(
            @Param("monthStart") OffsetDateTime monthStart,
            @Param("monthEnd") OffsetDateTime monthEnd,
            @Param("search") String search,
            Pageable pageable
    );

    @Query(value = """
    SELECT
        ws.id AS id,
        ws.work_date as workDate,
        u.id as supervisorId,
        u.full_name as supervisorFullName,
        ws.start_at AS startAt,
        ws.end_at AS endAt,
        ws.total_minutes as totalMinutes,
        ws.note as note,
        e.id as employeeId,
        e.full_name AS employeeName
    FROM work_shifts ws
    JOIN employees e ON ws.employee_id = e.id
    JOIN users u ON ws.supervisor_id = u.id
    WHERE ws.employee_id = :employeeId
    AND ws.start_at >= :monthStart
    AND ws.start_at < :monthEnd
    AND ws.is_active = true
    ORDER BY ws.start_at DESC
    """, nativeQuery = true)
    List<WorkShiftDetailInfo> getShiftsForMonth(
            @Param("employeeId") Long employeeId,
            @Param("monthStart") OffsetDateTime monthStart,
            @Param("monthEnd") OffsetDateTime monthEnd);

    @Query(value = """
    SELECT ws.id
    FROM work_shifts ws
    WHERE ws.employee_record_id = :employeeRecordId
      AND ws.is_active = true
    ORDER BY ws.start_at DESC
    """, nativeQuery = true)
    List<Long> getShiftsForEmployeeRecord(@Param("employeeRecordId") Long employeeRecordId);

    @Query(value = """
    SELECT ws.id
    FROM work_shifts ws
    WHERE ws.employee_record_id = :employeeRecordId
      AND ws.is_active = true
      AND ws.work_date BETWEEN :fromDate AND :toDate
    ORDER BY ws.start_at DESC
    """, nativeQuery = true)
    List<Long> getShiftsForEmployeeRecordInDateRange(
            @Param("employeeRecordId") Long employeeRecordId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );

    @Query(value = """
    SELECT ws.id
    FROM work_shifts ws
    WHERE ws.employee_record_id = :employeeRecordId
      AND ws.is_active = true
      AND ws.work_date = :workDate
    ORDER BY ws.start_at DESC
    """, nativeQuery = true)
    List<Long> getShiftsForEmployeeRecordOnDate(
            @Param("employeeRecordId") Long employeeRecordId,
            @Param("workDate") LocalDate workDate
    );

    List<WorkShift> findByEmployee_IdAndWorkDateIn(Long employeeId, List<LocalDate> workDates);

    /**
     * One employee's active shifts on or after a date.
     *
     * <p>Used when a compensation-scheme change invalidates work from its
     * effective date onward: only that employee, and only from that date, so the
     * invalidation never touches unrelated employees or earlier periods whose
     * calculation did not change.
     */
    @Query("""
            SELECT ws FROM WorkShift ws
            WHERE ws.employee.id = :employeeId
              AND ws.isActive = true
              AND ws.workDate >= :fromDate
            ORDER BY ws.workDate ASC
            """)
    List<WorkShift> findActiveByEmployeeFromDate(@Param("employeeId") Long employeeId,
                                                 @Param("fromDate") LocalDate fromDate);

    /**
     * Every active shift of this employee whose time overlaps [start, end).
     *
     * <p>Asked BEFORE the insert so the user gets a question instead of
     * {@code ex_work_shifts_no_overlap} arriving as a raw SQL error. The
     * constraint stays the guarantee — this is only what lets the application
     * explain the collision and offer a way out.
     *
     * <p>Half-open on purpose, matching the tstzrange the constraint builds:
     * a shift that ends exactly when another begins does not overlap it.
     */
    @Query("""
        SELECT ws FROM WorkShift ws
        JOIN FETCH ws.shift
        WHERE ws.employee.id = :employeeId
          AND ws.isActive = true
          AND ws.startAt < :end
          AND ws.endAt   > :start
        ORDER BY ws.startAt
        """)
    List<WorkShift> findOverlapping(@Param("employeeId") Long employeeId,
                                    @Param("start") OffsetDateTime start,
                                    @Param("end") OffsetDateTime end);
}
