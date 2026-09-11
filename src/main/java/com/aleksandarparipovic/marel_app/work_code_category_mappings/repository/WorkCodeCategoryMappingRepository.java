package com.aleksandarparipovic.marel_app.work_code_category_mappings.repository;

import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkCodeCategoryMappingRepository extends JpaRepository<WorkCodeCategoryMapping, Long> {

    List<WorkCodeCategoryMapping> findByIsActiveTrueAndArchivedAtIsNullOrderByIdAsc();

    Optional<WorkCodeCategoryMapping> findByIdAndArchivedAtIsNull(Long id);

    @Query("""
            SELECT m FROM WorkCodeCategoryMapping m
            JOIN FETCH m.sourceCategory
            JOIN FETCH m.targetCategory
            WHERE m.isActive = true
              AND m.archivedAt IS NULL
              AND m.mappingType IN :types
              AND m.validFrom <= :date
              AND (m.validUntil IS NULL OR m.validUntil >= :date)
            """)
    List<WorkCodeCategoryMapping> findActiveByTypesAndDate(
            @Param("types") Collection<String> types,
            @Param("date") LocalDate date
    );

    /**
     * The OPEN mappings of the given types — no closing date yet.
     *
     * <p>The šifarnik's view of a category's bonus pair: an open mapping is a
     * pair that stands, a closed one is a pair that was switched off (or
     * superseded by a re-version). Both source and target are versioned rows,
     * so an open mapping always points at the open version on each side.
     */
    List<WorkCodeCategoryMapping> findByMappingTypeInAndValidUntilIsNullAndIsActiveTrueAndArchivedAtIsNull(
            Collection<String> types);
}

