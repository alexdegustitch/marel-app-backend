package com.aleksandarparipovic.marel_app.product_variant;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    @EntityGraph(attributePaths = {"values", "values.attribute", "values.option"})
    List<ProductVariant> findByProduct_IdOrderByIdAsc(Long productId);

    @EntityGraph(attributePaths = {"values", "values.attribute", "values.option"})
    List<ProductVariant> findByProduct_IdAndArchivedAtIsNullOrderByIdAsc(Long productId);

    @EntityGraph(attributePaths = {"values", "values.attribute", "values.option"})
    List<ProductVariant> findByProduct_IdInAndArchivedAtIsNullOrderByIdAsc(Collection<Long> productIds);

    @EntityGraph(attributePaths = {"values", "values.attribute", "values.option"})
    Optional<ProductVariant> findWithValuesById(Long id);

    boolean existsByProduct_IdAndSignatureAndArchivedAtIsNull(Long productId, String signature);

    boolean existsByProduct_IdAndArchivedAtIsNull(Long productId);

    /**
     * The live variants of the type's products that carry NO value for the
     * attribute — what would fall short of a "required" flag about to be turned
     * on. Read before the flip, so the person turning it on sees exactly which
     * variants the new rule leaves without a value.
     */
    @Query("""
        SELECT v FROM ProductVariant v
        WHERE v.archivedAt IS NULL
          AND v.product.productType.id = :typeId
          AND NOT EXISTS (
              SELECT 1 FROM ProductVariantValue pv
              WHERE pv.variant = v AND pv.attribute.id = :attributeId)
        ORDER BY v.product.id ASC, v.id ASC
        """)
    List<ProductVariant> findLiveMissingAttributeValue(
            @Param("typeId") Long typeId,
            @Param("attributeId") Long attributeId);

    /**
     * Whether anything references the variant — the freeze line (SPEC odluka 13).
     * Order lines and manufacturing times are the only tables V78 pointed at it.
     */
    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM production_order_line_items WHERE product_variant_id = :id)
            OR EXISTS (SELECT 1 FROM sample_order_line_items     WHERE product_variant_id = :id)
            OR EXISTS (SELECT 1 FROM product_manufacturing_times WHERE product_variant_id = :id)
        """, nativeQuery = true)
    boolean isReferenced(@Param("id") Long id);
}
