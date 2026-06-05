package com.aleksandarparipovic.marel_app.daily_report;

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
}
