package com.aleksandarparipovic.marel_app.payroll_run_item;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface PayrollRunItemRepository extends JpaRepository<PayrollRunItem, Long>, JpaSpecificationExecutor<PayrollRunItem> {
}
