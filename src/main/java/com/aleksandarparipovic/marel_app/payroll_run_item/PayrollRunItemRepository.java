package com.aleksandarparipovic.marel_app.payroll_run_item;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollRunItemRepository extends JpaRepository<PayrollRunItem, Long>, JpaSpecificationExecutor<PayrollRunItem> {

    /** Fetches the item with its linked MonthlyReport in a single query — needed for version comparison. */
    @Query("SELECT pri FROM PayrollRunItem pri LEFT JOIN FETCH pri.monthlyReport WHERE pri.id = :id")
    Optional<PayrollRunItem> findByIdWithMonthlyReport(@Param("id") Long id);

    List<PayrollRunItem> findByPayrollRun_IdAndEmployee_Id(Long payrollRunId, Long employeeId);

    List<PayrollRunItem> findByPayrollRun_Id(Long payrollRunId);
}
