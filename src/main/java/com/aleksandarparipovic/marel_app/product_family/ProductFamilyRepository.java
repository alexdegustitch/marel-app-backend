package com.aleksandarparipovic.marel_app.product_family;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductFamilyRepository
        extends JpaRepository<ProductFamily, Long>, JpaSpecificationExecutor<ProductFamily> {

    List<ProductFamily> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    /*
     * "Does anybody ELSE already have this name" — takes an id to exclude so
     * saving a family without renaming it does not find itself and refuse. The
     * database has the last word: uq_product_families_name_ci is a unique index;
     * this exists so the refusal is a sentence a person can read.
     */
    @Query("""
        SELECT COUNT(f) > 0 FROM ProductFamily f
        WHERE LOWER(f.name) = LOWER(:name)
          AND (:excludeId IS NULL OR f.id <> :excludeId)
        """)
    boolean nameTakenByAnother(@Param("name") String name, @Param("excludeId") Long excludeId);
}
