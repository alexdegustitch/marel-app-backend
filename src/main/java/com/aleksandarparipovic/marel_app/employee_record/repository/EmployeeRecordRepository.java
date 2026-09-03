package com.aleksandarparipovic.marel_app.employee_record.repository;

import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordDto;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordEmployeeInfo;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordInfo;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordMonthAggregate;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordRecentDto;
import com.aleksandarparipovic.marel_app.employee_record.dto.EmployeeRecordSearchHit;
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
            (SELECT cs.code FROM employee_compensation_scheme_history h
                     JOIN compensation_schemes cs ON cs.id = h.compensation_scheme_id
                    WHERE h.employee_id = e.id AND h.valid_until IS NULL AND h.archived_at IS NULL
                    LIMIT 1) AS employeeSchemeCode,
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
        GROUP BY er.id, e.full_name, e.id, e.employee_no, d.name, bc.category_no,
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
            EXTRACT(YEAR FROM er.start_date)::int AS year,
            mr.approved_performance_rate AS approvedPerformanceRate,
            mr.total_shift_minutes       AS totalShiftMinutes
        FROM employee_records er
        JOIN employees e ON e.id = er.employee_id
        -- LEFT: a record exists before its monthly report is built, and the
        -- employee page must still list the month rather than drop it.
        LEFT JOIN monthly_reports mr ON mr.employee_record_id = er.id
        WHERE e.id = :employeeId
        ORDER BY er.start_date DESC
        LIMIT :size
        """, nativeQuery = true)
    List<RecentEmployeeRecordDto> findRecentByEmployeeId(@Param("employeeId") Long employeeId, @Param("size") int size);


    /**
     * Twelve months of a year summed up, one row per month that holds a karton.
     *
     * <p>One grouped query over the year's kartoni: how many, for how many
     * workers, how many shift minutes their monthly reports add up to, the plain
     * average of the approved performance rate over the ones that have it, and
     * when anybody last touched a karton of the month. Months with nothing in
     * them do not appear; the service fills them in.
     *
     * <p>The record count is DISTINCT so a karton with more than one monthly
     * report row (there should be one, but the schema allows a re-built period)
     * is still one karton.
     */
    @Query(value = """
        WITH recs AS (
            SELECT er.id,
                   er.employee_id,
                   EXTRACT(MONTH FROM er.start_date)::int AS month
            FROM employee_records er
            WHERE er.start_date >= :yearStart
              AND er.start_date <  :yearEnd
        ),
        totals AS (
            SELECT r.month,
                   COUNT(DISTINCT r.id)                     AS record_count,
                   COUNT(DISTINCT r.employee_id)            AS employee_count,
                   COALESCE(SUM(mr.total_shift_minutes), 0) AS total_shift_minutes,
                   AVG(mr.approved_performance_rate)        AS avg_performance_rate
            FROM recs r
            LEFT JOIN monthly_reports mr
                   ON mr.employee_record_id = r.id
                  AND mr.archived_at IS NULL
            GROUP BY r.month
        ),
        activity AS (
            SELECT r.month, MAX(eru.last_activity_at) AS last_activity_at
            FROM recs r
            JOIN employee_record_updates eru ON eru.employee_record_id = r.id
            GROUP BY r.month
        )
        SELECT t.month                AS month,
               t.record_count         AS recordCount,
               t.employee_count       AS employeeCount,
               t.total_shift_minutes  AS totalShiftMinutes,
               t.avg_performance_rate AS avgPerformanceRate,
               a.last_activity_at     AS lastActivityAt
        FROM totals t
        LEFT JOIN activity a ON a.month = t.month
        ORDER BY t.month
        """, nativeQuery = true)
    List<EmployeeRecordMonthAggregate> aggregateMonthsOfYear(@Param("yearStart") LocalDate yearStart,
                                                             @Param("yearEnd") LocalDate yearEnd);

    /**
     * The kartoni one user last had open, at most {@code perMonth} for each
     * month of the year, newest first within the month.
     *
     * <p>The same trail {@link #findLastThreePerMonthForSupervisor} reads, ranked
     * per month in one pass instead of once per month.
     */
    @Query(value = """
        WITH mine AS (
            SELECT er.id                                  AS record_id,
                   er.employee_id,
                   EXTRACT(MONTH FROM er.start_date)::int AS month,
                   eru.last_activity_at,
                   ROW_NUMBER() OVER (
                       PARTITION BY EXTRACT(MONTH FROM er.start_date)
                       ORDER BY eru.last_activity_at DESC, er.id DESC
                   ) AS rn
            FROM employee_record_updates eru
            JOIN employee_records er ON er.id = eru.employee_record_id
            WHERE eru.user_id = :userId
              AND er.start_date >= :yearStart
              AND er.start_date <  :yearEnd
        )
        SELECT m.month            AS month,
               m.record_id        AS employeeRecordId,
               e.id               AS employeeId,
               e.full_name        AS employeeName,
               m.last_activity_at AS updateTime
        FROM mine m
        JOIN employees e ON e.id = m.employee_id
        WHERE m.rn <= :perMonth
        ORDER BY m.month, m.last_activity_at DESC
        """, nativeQuery = true)
    List<EmployeeRecordRecentDto> findRecentPerMonthForUser(@Param("userId") Long userId,
                                                            @Param("yearStart") LocalDate yearStart,
                                                            @Param("yearEnd") LocalDate yearEnd,
                                                            @Param("perMonth") int perMonth);

    /**
     * The kartoni of one year whose worker's name or number contains a fragment.
     *
     * <p>Spelled {@code lower(column) LIKE} rather than ILIKE on purpose: that is
     * the expression the trigram indexes from V31 are built on, and an index is
     * only used by a query that spells its expression the same way. The pattern
     * arrives already lower-cased and escaped (see {@code LikePattern}).
     */
    @Query(value = """
        SELECT er.id                                  AS employeeRecordId,
               e.id                                   AS employeeId,
               e.full_name                            AS employeeName,
               e.employee_no                          AS employeeNo,
               EXTRACT(MONTH FROM er.start_date)::int AS month,
               EXTRACT(YEAR  FROM er.start_date)::int AS year
        FROM employee_records er
        JOIN employees e ON e.id = er.employee_id
        WHERE er.start_date >= :yearStart
          AND er.start_date <  :yearEnd
          AND (lower(e.full_name)   LIKE :pattern
            OR lower(e.employee_no) LIKE :pattern)
        ORDER BY er.start_date DESC, e.full_name ASC, er.id ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<EmployeeRecordSearchHit> searchInYear(@Param("yearStart") LocalDate yearStart,
                                               @Param("yearEnd") LocalDate yearEnd,
                                               @Param("pattern") String pattern,
                                               @Param("limit") int limit);
}
