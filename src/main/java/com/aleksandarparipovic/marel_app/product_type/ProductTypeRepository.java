package com.aleksandarparipovic.marel_app.product_type;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductTypeRepository
        extends JpaRepository<ProductType, Long>, JpaSpecificationExecutor<ProductType> {

    List<ProductType> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    List<ProductType> findByFamily_IdAndIsActiveTrueOrderBySortOrderAscNameAsc(Long familyId);

    /*
     * "Does anybody ELSE in THIS family already carry this code" — the uniqueness
     * is per family (uq_product_types_code_ci is on (family_id, lower(code))), so
     * the check is scoped to the family too. Takes an id to exclude so saving a
     * type without changing its code does not find itself and refuse.
     */
    @Query("""
        SELECT COUNT(t) > 0 FROM ProductType t
        WHERE t.family.id = :familyId
          AND LOWER(t.code) = LOWER(:code)
          AND (:excludeId IS NULL OR t.id <> :excludeId)
        """)
    boolean codeTakenInFamilyByAnother(
            @Param("familyId") Long familyId,
            @Param("code") String code,
            @Param("excludeId") Long excludeId);
}
