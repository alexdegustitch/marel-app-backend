package com.aleksandarparipovic.marel_app.monthly_report_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface MonthlyReportCategoryRepository extends JpaRepository<MonthlyReportCategory, Long>, JpaSpecificationExecutor<MonthlyReportCategory> {

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM monthly_report_categories WHERE monthly_report_id = :monthlyReportId",
           nativeQuery = true)
    void deleteAllByMonthlyReportId(@Param("monthlyReportId") Long monthlyReportId);
}
