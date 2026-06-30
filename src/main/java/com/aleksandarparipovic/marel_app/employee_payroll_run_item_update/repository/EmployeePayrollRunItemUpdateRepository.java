package com.aleksandarparipovic.marel_app.employee_payroll_run_item_update.repository;

import com.aleksandarparipovic.marel_app.employee_payroll_run_item_update.EmployeePayrollRunItemUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeePayrollRunItemUpdateRepository extends JpaRepository<EmployeePayrollRunItemUpdate, Long> {

    List<EmployeePayrollRunItemUpdate> findByPayrollRunItemIdOrderByLastActivityAtDesc(Long payrollRunItemId);

    List<EmployeePayrollRunItemUpdate> findByUserIdOrderByLastActivityAtDesc(Long userId);
}

