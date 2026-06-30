package com.aleksandarparipovic.marel_app.daily_report;

import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportChartInfo;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportEmployeeMonthlyInfo;
import com.aleksandarparipovic.marel_app.summary.dto.MonthlySummaryProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyReportRepository extends JpaRepository<DailyReport, Long>, JpaSpecificationExecutor<DailyReport> {

    @Query("SELECT dr FROM DailyReport dr WHERE dr.workShift.id = :workShiftId")
    Optional<DailyReport> findByWorkShiftId(@Param("workShiftId") Long workShiftId);

    List<DailyReport> findByEmployee_IdAndWorkDateBetween(Long employeeId, LocalDate start, LocalDate end);

    @Query(value = """
        SELECT
            dr.employee_id                              AS employeeId,
            EXTRACT(YEAR  FROM dr.work_date)::int       AS reportYear,
            EXTRACT(MONTH FROM dr.work_date)::int       AS reportMonth,
            COALESCE(SUM(dr.total_shift_minutes),       0) AS totalShiftMinutes,
            COALESCE(SUM(dr.total_work_minutes),        0) AS totalWorkMinutes,
            COALESCE(SUM(dr.total_quantity),            0) AS totalQuantity,
            COALESCE(SUM(dr.total_scrap),               0) AS totalScrap,
            COALESCE(SUM(dr.total_weighted_norm_minutes),0) AS totalEffectiveMinutes
        FROM daily_reports dr
        WHERE dr.employee_id = :employeeId
          AND EXTRACT(YEAR  FROM dr.work_date) = :year
          AND EXTRACT(MONTH FROM dr.work_date) = :month
        GROUP BY dr.employee_id,
                 EXTRACT(YEAR  FROM dr.work_date),
                 EXTRACT(MONTH FROM dr.work_date)
        """, nativeQuery = true)
    MonthlySummaryProjection getMonthlySummaryFromDailyReports(
            @Param("employeeId") Long employeeId,
            @Param("year") int year,
            @Param("month") int month);

    @Query(value = """
        SELECT
            dr.work_date AS workDate,
            dr.work_shift_id AS workShiftId,
            dr.approved_performance_rate AS approvedPerformanceRate
        FROM daily_reports dr
        join work_shifts ws on dr.work_shift_id = ws.id
        WHERE dr.employee_id = :employeeId
          AND dr.work_date BETWEEN :startDate AND :endDate
        ORDER BY dr.work_date ASC, ws.start_at
        """, nativeQuery = true)
    List<DailyReportChartInfo> findChartInfoByEmployeeAndPeriod(
            @Param("employeeId") Long employeeId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query(value = """
        SELECT
            dr.work_date AS workDate,
            dr.total_approved_minutes AS totalApprovedMinutes,
            dr.approved_performance_rate AS approvedPerformanceRate,
            dr.meals_count AS mealsCount,
            dr.total_shift_minutes AS totalShiftMinutes
        FROM daily_reports dr
        WHERE dr.employee_id = :employeeId
          AND dr.work_date BETWEEN :startDate AND :endDate
        ORDER BY dr.work_date ASC
        """, nativeQuery = true)
    List<DailyReportEmployeeMonthlyInfo> findEmployeeMonthlyInfoByPeriod(
            @Param("employeeId") Long employeeId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query(value = """
        WITH required_days AS (
            SELECT generate_series(
                CAST(:weekStart AS date),
                CAST(:workDate AS date) - INTERVAL '1 day',
                INTERVAL '1 day'
            )::date AS work_date
        )
        SELECT CAST(COUNT(*) AS integer)
        FROM required_days rd
        LEFT JOIN daily_reports dr ON dr.employee_id = :employeeId AND dr.work_date = rd.work_date
        WHERE COALESCE(dr.bonus_eligible_minutes, 0) < :minBonusMinutes
        """, nativeQuery = true)
    Integer countPreviousDaysWithInsufficientBonusMinutes(
            @Param("employeeId") Long employeeId,
            @Param("weekStart") LocalDate weekStart,
            @Param("workDate") LocalDate workDate,
            @Param("minBonusMinutes") int minBonusMinutes
    );
}
