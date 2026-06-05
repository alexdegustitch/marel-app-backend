package com.aleksandarparipovic.marel_app.daily_report_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface DailyReportCategoryRepository extends JpaRepository<DailyReportCategory, Long>, JpaSpecificationExecutor<DailyReportCategory> {

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM daily_report_categories WHERE daily_report_id = :dailyReportId",
           nativeQuery = true)
    void deleteAllByDailyReportId(@Param("dailyReportId") Long dailyReportId);

    /** Load categories for multiple daily reports with workCodeCategory eagerly fetched. */
    @Query("SELECT dc FROM DailyReportCategory dc LEFT JOIN FETCH dc.workCodeCategory WHERE dc.dailyReport.id IN :dailyReportIds")
    List<DailyReportCategory> findAllByDailyReportIds(@Param("dailyReportIds") List<Long> dailyReportIds);
}
