package com.aleksandarparipovic.marel_app.payroll_run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long>, JpaSpecificationExecutor<PayrollRun> {

    Optional<PayrollRun> findFirstByReportYearAndReportMonth(int year, int month);
}
