package com.aleksandarparipovic.marel_app.payroll_time_adjustment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollTimeAdjustmentCategoryRepository
        extends JpaRepository<PayrollTimeAdjustmentCategory, Long> {

    Optional<PayrollTimeAdjustmentCategory> findByCode(String code);

    List<PayrollTimeAdjustmentCategory> findByIsActiveTrueAndArchivedAtIsNullOrderBySortOrderAscCodeAsc();
}
