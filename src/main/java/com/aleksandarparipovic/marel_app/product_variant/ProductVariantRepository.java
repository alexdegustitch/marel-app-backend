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
