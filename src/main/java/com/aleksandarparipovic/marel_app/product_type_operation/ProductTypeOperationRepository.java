package com.aleksandarparipovic.marel_app.product_type_operation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductTypeOperationRepository extends JpaRepository<ProductTypeOperation, Long> {

    /** The whole template of a type, in order — for the admin screen. */
    List<ProductTypeOperation> findByProductType_IdOrderBySortOrderAscOpNameAsc(Long productTypeId);

    /** The active template of a type, in order — the set a product form offers to copy. */
    List<ProductTypeOperation> findByProductType_IdAndIsActiveTrueOrderBySortOrderAscOpNameAsc(Long productTypeId);

    /*
     * "Does another template operation of THIS type already carry this name" —
     * uniqueness is per type (uq_product_type_operations_type_op_name_ci). Excludes
     * an id so saving without renaming does not find itself and refuse.
     */
    @Query("""
        SELECT COUNT(o) > 0 FROM ProductTypeOperation o
        WHERE o.productType.id = :productTypeId
          AND LOWER(o.opName) = LOWER(:opName)
          AND (:excludeId IS NULL OR o.id <> :excludeId)
        """)
    boolean opNameTakenInTypeByAnother(
            @Param("productTypeId") Long productTypeId,
            @Param("opName") String opName,
            @Param("excludeId") Long excludeId);
}
