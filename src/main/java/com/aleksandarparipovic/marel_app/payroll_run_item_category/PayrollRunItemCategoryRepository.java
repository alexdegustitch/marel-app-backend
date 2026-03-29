package com.aleksandarparipovic.marel_app.payroll_run_item_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface PayrollRunItemCategoryRepository extends JpaRepository<PayrollRunItemCategory, Long>, JpaSpecificationExecutor<PayrollRunItemCategory> {
}
