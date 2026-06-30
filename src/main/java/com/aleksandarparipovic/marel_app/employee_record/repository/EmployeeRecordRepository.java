package com.aleksandarparipovic.marel_app.employee_record.repository;

import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordDto;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordEmployeeInfo;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordInfo;
import com.aleksandarparipovic.marel_app.employee_record.dto.RecentEmployeeRecordDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRecordRepository extends JpaRepository<EmployeeRecord, Long> {

    Optional<EmployeeRecord> findByEmployeeIdAndStartDate(Long employeeId, LocalDate startDate);

    boolean existsByEmployeeIdAndStartDate(Long employeeId, LocalDate startDate);


    @Query(value = """
        SELECT
            er.id AS employeeRecordId,
            e.full_name AS employeeName,
            e.id AS employeeId,
            EXTRACT(MONTH FROM er.start_date)::int AS month,
            EXTRACT(YEAR FROM er.start_date)::int AS year,
            eru.last_activity_at AS updateTime
        FROM employee_record_updates eru
        JOIN employee_records er ON er.id = eru.employee_record_id
        JOIN employees e ON e.id = er.employee_id
        WHERE eru.user_id = :userId
          AND er.start_date >= CAST(:monthStart AS date)
          AND er.start_date < CAST(:monthEnd AS date)
        ORDER BY eru.last_activity_at DESC
        LIMIT 3
        """, nativeQuery = true)
    List<EmployeeRecordDto> findLastThreePerMonthForSupervisor(@Param("userId") Long userId,
                                                                @Param("monthStart") OffsetDateTime monthStart,
                                                                @Param("monthEnd") OffsetDateTime monthEnd);

    @Query(
            value = """
        SELECT
            er.id AS id,
            e.full_name AS employeeName,
            e.id AS employeeId,
            e.employee_no AS employeeNo,
            e.is_foreigner AS employeeForeigner,
            d.name AS employeeDepartment,
            bc.category_no AS employeeBonus,
            MAX(eru.last_activity_at) AS updateTime,
            mr.total_shift_minutes AS totalShiftMinutes,
            mr.approved_performance_rate AS approvedPerformanceRate
        FROM employee_records er
        JOIN employees e ON e.id = er.employee_id
        JOIN departments d ON d.id = e.department_id
        LEFT JOIN employees_bonus_history eb
            ON eb.employee_id = e.id
           AND eb.end_date IS NULL
        LEFT JOIN bonus_categories bc ON bc.id = eb.bonus_category_id
        LEFT JOIN employee_record_updates eru ON eru.employee_record_id = er.id
        LEFT JOIN monthly_reports mr ON mr.employee_record_id = er.id
        WHERE er.start_date >= CAST(:monthStart AS date)
          AND er.start_date < CAST(:monthEnd AS date)
          AND (
                :search IS NULL
                OR e.full_name ILIKE '%' || :search || '%'
                OR e.employee_no ILIKE '%' || :search || '%'
              )
        GROUP BY er.id, e.full_name, e.id, e.employee_no, e.is_foreigner, d.name, bc.category_no,
                 mr.total_shift_minutes, mr.approved_performance_rate
    """,
            countQuery = """
        SELECT COUNT(er.id)
        FROM employee_records er
        JOIN employees e ON e.id = er.employee_id
        WHERE er.start_date >= CAST(:monthStart AS date)
          AND er.start_date < CAST(:monthEnd AS date)
          AND (
                :search IS NULL
                OR e.full_name ILIKE '%' || :search || '%'
                OR e.employee_no ILIKE '%' || :search || '%'
              )
    """,
            nativeQuery = true
    )
    Page<EmployeeRecordInfo> findMonthlyRecords(
            @Param("monthStart") OffsetDateTime monthStart,
            @Param("monthEnd") OffsetDateTime monthEnd,
            @Param("search") String search,
            Pageable pageable
    );

    @Query(value = """
        SELECT
            e.full_name AS employeeName,
            e.id AS employeeId,
            EXTRACT(MONTH FROM er.start_date)::int AS month,
            EXTRACT(YEAR FROM er.start_date)::int AS year
        FROM employee_records er
        JOIN employees e ON e.id = er.employee_id
        WHERE er.id = :id
        """, nativeQuery = true)
    Optional<EmployeeRecordEmployeeInfo> findDtoById(@Param("id") Long id);

    @Query(value = """
        SELECT
            er.id AS id,
            e.full_name AS employeeName,
            e.id AS employeeId,
            EXTRACT(MONTH FROM er.start_date)::int AS month,
            EXTRACT(YEAR FROM er.start_date)::int AS year
        FROM employee_records er
        JOIN employees e ON e.id = er.employee_id
        WHERE e.id = :employeeId
        ORDER BY er.start_date DESC
        LIMIT :size
        """, nativeQuery = true)
    List<RecentEmployeeRecordDto> findRecentByEmployeeId(@Param("employeeId") Long employeeId, @Param("size") int size);

}


