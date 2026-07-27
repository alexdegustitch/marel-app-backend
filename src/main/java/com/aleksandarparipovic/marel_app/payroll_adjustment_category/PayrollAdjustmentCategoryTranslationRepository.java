package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollAdjustmentCategoryTranslationRepository
        extends JpaRepository<PayrollAdjustmentCategoryTranslation, Long> {

    /** Every translation for one locale, in one query — see the work-code equivalent. */
    @Query("""
            SELECT t FROM PayrollAdjustmentCategoryTranslation t
            WHERE lower(t.locale) = lower(:locale)
            """)
    List<PayrollAdjustmentCategoryTranslation> findAllByLocale(@Param("locale") String locale);

    @Query("""
            SELECT t FROM PayrollAdjustmentCategoryTranslation t
            WHERE t.payrollAdjustmentCategory.id = :categoryId
              AND lower(t.locale) = lower(:locale)
            """)
    Optional<PayrollAdjustmentCategoryTranslation> findByCategoryAndLocale(
            @Param("categoryId") Long categoryId,
            @Param("locale") String locale);

    @Query("""
            SELECT t FROM PayrollAdjustmentCategoryTranslation t
            WHERE t.payrollAdjustmentCategory.id = :categoryId
            ORDER BY t.locale ASC
            """)
    List<PayrollAdjustmentCategoryTranslation> findByCategory(@Param("categoryId") Long categoryId);
}
