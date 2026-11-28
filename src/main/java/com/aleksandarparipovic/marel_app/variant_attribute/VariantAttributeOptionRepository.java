package com.aleksandarparipovic.marel_app.variant_attribute;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface VariantAttributeOptionRepository extends JpaRepository<VariantAttributeOption, Long> {

    List<VariantAttributeOption> findByAttribute_IdOrderBySortOrderAscValueAsc(Long attributeId);

    List<VariantAttributeOption> findByAttribute_IdInOrderBySortOrderAscValueAsc(Collection<Long> attributeIds);

    @Query("""
        SELECT COUNT(o) > 0 FROM VariantAttributeOption o
        WHERE o.attribute.id = :attributeId
          AND LOWER(o.value) = LOWER(:value)
          AND o.archivedAt IS NULL
          AND (:excludeId IS NULL OR o.id <> :excludeId)
        """)
    boolean valueTakenInAttributeByAnother(
            @Param("attributeId") Long attributeId,
            @Param("value") String value,
            @Param("excludeId") Long excludeId);
}
