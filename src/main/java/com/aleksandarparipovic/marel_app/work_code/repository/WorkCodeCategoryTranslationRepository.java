package com.aleksandarparipovic.marel_app.work_code.repository;

import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategoryTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkCodeCategoryTranslationRepository extends JpaRepository<WorkCodeCategoryTranslation, Long> {

    /**
     * Every translation for one locale, in one query.
     *
     * <p>This is the only shape the read path uses. Fetching a translation per
     * category inside a payroll loop would turn one payslip into dozens of
     * round-trips, so callers load this map once and index it by category id.
     */
    @Query("""
            SELECT t FROM WorkCodeCategoryTranslation t
            WHERE lower(t.locale) = lower(:locale)
            """)
    List<WorkCodeCategoryTranslation> findAllByLocale(@Param("locale") String locale);

    @Query("""
            SELECT t FROM WorkCodeCategoryTranslation t
            WHERE t.workCodeCategory.id = :categoryId
              AND lower(t.locale) = lower(:locale)
            """)
    Optional<WorkCodeCategoryTranslation> findByCategoryAndLocale(
            @Param("categoryId") Long categoryId,
            @Param("locale") String locale);

    @Query("""
            SELECT t FROM WorkCodeCategoryTranslation t
            WHERE t.workCodeCategory.id = :categoryId
            ORDER BY t.locale ASC
            """)
    List<WorkCodeCategoryTranslation> findByCategory(@Param("categoryId") Long categoryId);
}
