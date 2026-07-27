package com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository;

import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WorkCodeCategorySchemeRuleRepository extends JpaRepository<WorkCodeCategorySchemeRule, Long> {

    /**
     * Every rule of one scheme in force on {@code date}.
     *
     * <p>The resolver loads the whole scheme's rule set once and indexes it by
     * source category, rather than issuing one query per work log — a payroll
     * month for one employee can be hundreds of logs and they all share a scheme.
     *
     * <p>Inactive, archived, not-yet-valid and expired rules are excluded here so
     * no caller has to remember to filter them.
     */
    @Query("""
            SELECT r FROM WorkCodeCategorySchemeRule r
            JOIN FETCH r.sourceCategory
            LEFT JOIN FETCH r.effectiveCategory
            WHERE r.compensationScheme.id = :schemeId
              AND r.isActive = true
              AND r.archivedAt IS NULL
              AND r.validFrom <= :date
              AND (r.validUntil IS NULL OR r.validUntil >= :date)
            """)
    List<WorkCodeCategorySchemeRule> findActiveForSchemeAt(
            @Param("schemeId") Long schemeId,
            @Param("date") LocalDate date);

    /** All rules of one scheme, including inactive ones — administration screens. */
    @Query("""
            SELECT r FROM WorkCodeCategorySchemeRule r
            JOIN FETCH r.sourceCategory
            LEFT JOIN FETCH r.effectiveCategory
            WHERE r.compensationScheme.id = :schemeId
              AND r.archivedAt IS NULL
            ORDER BY r.sourceCategory.displayOrder ASC, r.validFrom ASC
            """)
    List<WorkCodeCategorySchemeRule> findAllForScheme(@Param("schemeId") Long schemeId);
}
