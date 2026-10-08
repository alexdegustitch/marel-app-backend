package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PayrollAdjustmentSectionRepository extends JpaRepository<PayrollAdjustmentSection, Long> {

    Optional<PayrollAdjustmentSection> findByCode(String code);
}
