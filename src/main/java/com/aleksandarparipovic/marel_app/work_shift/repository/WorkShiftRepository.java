package com.aleksandarparipovic.marel_app.work_shift.repository;

import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftDto;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

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
                MAX(ws.last_activity_at) AS last_activity
            FROM work_shifts ws
            WHERE ws.supervisor_id = :userId
              AND ws.start_at >= make_date(:year,1,1)
              AND ws.start_at < make_date(:year+1,1,1)
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
            e.full_name AS employeeName,
            r.employee_id AS employeeId,
            EXTRACT(MONTH FROM r.month)::int AS month,
            :year AS year,
            r.last_activity AS updateTime
        FROM ranked r
        JOIN employees e
            ON e.id = r.employee_id
        WHERE r.rn <= 3
        ORDER BY r.month DESC, r.last_activity DESC, e.full_name ASC
        """, nativeQuery = true)
    List<WorkShiftDto> findLastThreePerMonthForSupervisor(Long userId, int year);

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
                   e.is_foreigner as employeeForeigner,
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
            GROUP BY e.id, e.full_name, e.employee_no, e.is_foreigner, e.department_id, eb.bonus_category_id
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
}
