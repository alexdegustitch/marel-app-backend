package com.aleksandarparipovic.marel_app.monthly_report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long>, JpaSpecificationExecutor<MonthlyReport> {
}
