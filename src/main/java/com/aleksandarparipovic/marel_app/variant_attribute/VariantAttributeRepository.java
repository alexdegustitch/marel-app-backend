package com.aleksandarparipovic.marel_app.variant_attribute;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VariantAttributeRepository extends JpaRepository<VariantAttribute, Long> {

    /** Every attribute of a type, archived ones included — the admin screen shows both. */
    List<VariantAttribute> findByProductType_IdOrderBySortOrderAscNameAsc(Long productTypeId);

    /** The live attributes of a type — what a variant form offers. */
    List<VariantAttribute> findByProductType_IdAndArchivedAtIsNullOrderBySortOrderAscNameAsc(Long productTypeId);

    @Query("""
        SELECT COUNT(a) > 0 FROM VariantAttribute a
        WHERE a.productType.id = :productTypeId
          AND LOWER(a.name) = LOWER(:name)
          AND a.archivedAt IS NULL
          AND (:excludeId IS NULL OR a.id <> :excludeId)
        """)
    boolean nameTakenInTypeByAnother(
            @Param("productTypeId") Long productTypeId,
            @Param("name") String name,
            @Param("excludeId") Long excludeId);
}
