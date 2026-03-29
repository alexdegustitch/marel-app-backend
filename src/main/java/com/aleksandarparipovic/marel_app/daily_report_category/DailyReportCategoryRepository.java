package com.aleksandarparipovic.marel_app.daily_report_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyReportCategoryRepository extends JpaRepository<DailyReportCategory, Long>, JpaSpecificationExecutor<DailyReportCategory> {
}
