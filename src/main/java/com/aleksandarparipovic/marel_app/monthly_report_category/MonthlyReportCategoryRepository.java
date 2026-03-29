package com.aleksandarparipovic.marel_app.monthly_report_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface MonthlyReportCategoryRepository extends JpaRepository<MonthlyReportCategory, Long>, JpaSpecificationExecutor<MonthlyReportCategory> {
}
