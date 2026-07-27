package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PayrollAdjustmentCategorySchemeRuleRepository
        extends JpaRepository<PayrollAdjustmentCategorySchemeRule, Long> {

    /**
     * Every in-force rule of one scheme that overlaps the period
     * {@code [from, to]}.
     *
     * <p>A range rather than a single date because a payroll month is a range,
     * and a rule that starts or ends mid-month still governs part of it.
     */
    @Query("""
            SELECT r FROM PayrollAdjustmentCategorySchemeRule r
            JOIN FETCH r.payrollAdjustmentCategory
            WHERE r.compensationScheme.id = :schemeId
              AND r.isActive = true
              AND r.archivedAt IS NULL
              AND r.validFrom <= :to
              AND (r.validUntil IS NULL OR r.validUntil >= :from)
            """)
    List<PayrollAdjustmentCategorySchemeRule> findInForceForSchemeBetween(
            @Param("schemeId") Long schemeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
