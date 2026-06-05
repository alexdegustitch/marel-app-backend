package com.aleksandarparipovic.marel_app.monthly_report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long>, JpaSpecificationExecutor<MonthlyReport> {

    Optional<MonthlyReport> findByEmployee_IdAndReportYearAndReportMonth(
            Long employeeId, Integer year, Integer month);
}
