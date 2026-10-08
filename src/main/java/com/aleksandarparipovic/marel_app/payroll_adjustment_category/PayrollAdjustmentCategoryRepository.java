package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PayrollAdjustmentCategoryRepository extends JpaRepository<PayrollAdjustmentCategory, Long>, JpaSpecificationExecutor<PayrollAdjustmentCategory> {

    /** Lookup by the stable business code. */
    java.util.Optional<PayrollAdjustmentCategory> findByCode(String code);

    List<PayrollAdjustmentCategory> findByIsActiveTrueAndArchivedAtIsNull();

    /**
     * The set payroll initialisation seeds on every item — excludes carrier
     * categories for hand-added lines (seed_on_init = false, e.g. CUSTOM).
     */
    List<PayrollAdjustmentCategory> findByIsActiveTrueAndArchivedAtIsNullAndSeedOnInitTrue();
}
