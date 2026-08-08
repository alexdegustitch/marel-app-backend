package com.aleksandarparipovic.marel_app.work_code_category_mappings.repository;

import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMappingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkCodeCategoryMappingTypeRepository
        extends JpaRepository<WorkCodeCategoryMappingType, Long> {

    Optional<WorkCodeCategoryMappingType> findByCode(String code);

    /**
     * The codes an employee on probation must NOT be given.
     *
     * <p>Asked as "which are withheld" rather than "which apply" on purpose: the
     * caller starts from the types the context produced and removes from them, so
     * a type missing from the registry entirely cannot accidentally be dropped.
     * It also keeps the query to the short list — one or two rows — instead of
     * loading the registry to filter in memory.
     */
    @Query("""
            SELECT t.code FROM WorkCodeCategoryMappingType t
            WHERE t.isActive = true AND t.appliesDuringProbation = false
            """)
    List<String> findCodesWithheldDuringProbation();
}
