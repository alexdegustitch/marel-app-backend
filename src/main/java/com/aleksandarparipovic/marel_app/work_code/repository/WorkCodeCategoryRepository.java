package com.aleksandarparipovic.marel_app.work_code.repository;

import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkCodeCategoryRepository extends JpaRepository<WorkCodeCategory, Long>, JpaSpecificationExecutor<WorkCodeCategory> {
    List<WorkCodeCategory> findByArchivedAtIsNullOrderByDisplayOrderAscIdAsc();

    List<WorkCodeCategory> findByIsActiveTrueAndArchivedAtIsNullOrderByDisplayOrderAscIdAsc();

    @Query(value = """
            SELECT wcc.norm_multiplier
            FROM work_code_categories wcc
            WHERE wcc.category_no = :categoryNo
              AND (wcc.valid_from IS NULL OR wcc.valid_from <= :atDate)
              AND (wcc.valid_until IS NULL OR wcc.valid_until >= :atDate)
            ORDER BY wcc.valid_from DESC NULLS LAST
            LIMIT 1
            """, nativeQuery = true)
    Optional<BigDecimal> findEffectiveNormMultiplier(@Param("categoryNo") String categoryNo,
                                                     @Param("atDate") LocalDate atDate);

    /**
     * The version of a category in force on a date, found by CODE.
     *
     * <p>By code and not by id for the reason the dashboard already gives: a
     * category is re-versioned over time and every version keeps the code the
     * factory knows it by. An id pinned in Java stops meaning anything the first
     * time somebody re-versions it.
     *
     * <p>Same ordering as findEffectiveNormMultiplier, so the two can never
     * disagree about which version is in force.
     */
    @Query(value = """
            SELECT *
            FROM work_code_categories wcc
            WHERE wcc.category_no = :categoryNo
              AND wcc.archived_at IS NULL
              AND (wcc.valid_from IS NULL OR wcc.valid_from <= :atDate)
              AND (wcc.valid_until IS NULL OR wcc.valid_until >= :atDate)
            ORDER BY wcc.valid_from DESC NULLS LAST
            LIMIT 1
            """, nativeQuery = true)
    Optional<WorkCodeCategory> findInForceByCategoryNo(@Param("categoryNo") String categoryNo,
                                                       @Param("atDate") LocalDate atDate);
}
