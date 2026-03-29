package com.aleksandarparipovic.marel_app.payroll_adjustment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface PayrollAdjustmentRepository extends JpaRepository<PayrollAdjustment, Long>, JpaSpecificationExecutor<PayrollAdjustment> {
}
