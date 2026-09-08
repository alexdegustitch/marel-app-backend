package com.aleksandarparipovic.marel_app.product_type_attribute;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductTypeAttributeRepository extends JpaRepository<ProductTypeAttribute, Long> {

    /** Every attribute of a type, in column order — for the schema admin screen. */
    List<ProductTypeAttribute> findByProductType_IdOrderBySortOrderAscNameAsc(Long productTypeId);

    /** The active attributes of a type, in column order — the set a product form fills in. */
    List<ProductTypeAttribute> findByProductType_IdAndIsActiveTrueOrderBySortOrderAscNameAsc(Long productTypeId);

    /*
     * "Does another attribute of THIS type already carry this name" — uniqueness
     * is per type (uq_product_type_attributes_type_name_ci). Excludes an id so
     * saving without renaming does not find itself and refuse.
     */
    @Query("""
        SELECT COUNT(a) > 0 FROM ProductTypeAttribute a
        WHERE a.productType.id = :productTypeId
          AND LOWER(a.name) = LOWER(:name)
          AND (:excludeId IS NULL OR a.id <> :excludeId)
        """)
    boolean nameTakenInTypeByAnother(
            @Param("productTypeId") Long productTypeId,
            @Param("name") String name,
            @Param("excludeId") Long excludeId);
}
