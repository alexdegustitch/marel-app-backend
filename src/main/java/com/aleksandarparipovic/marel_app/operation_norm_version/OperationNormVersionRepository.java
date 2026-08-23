package com.aleksandarparipovic.marel_app.operation_norm_version;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperationNormVersionRepository extends JpaRepository<OperationNormVersion, Long> {

    /** The live history — newest first, as the screen reads it. */
    List<OperationNormVersion> findByOperation_IdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(Long operationId);

    /** The whole history, archived norms included. */
    List<OperationNormVersion> findByOperation_IdOrderByCreatedAtDescIdDesc(Long operationId);

    /** The norm in force, as the operation itself states it. */
    Optional<OperationNormVersion> findFirstByOperation_IdAndCurrentTrue(Long operationId);

    /**
     * The operations of one product whose norm in force is a temporary one.
     *
     * <p>Asked for the whole product at once: the manufacturing-time screen lists
     * every operation of a product, and one query per row would be a query per row.
     */
    @Query("""
        SELECT v.operation.id
        FROM OperationNormVersion v
        WHERE v.operation.product.id = :productId
          AND v.current = true
          AND v.temporary = true
        """)
    List<Long> findOperationIdsWithTemporaryNorm(@Param("productId") Long productId);

    /**
     * The norm that inherits when the one in force is archived.
     *
     * <p>Most recent by the date it applies FROM — that is the norm the shop
     * floor would work to next. A temporary norm has no such date on purpose,
     * so it is ranked by when it was entered instead; that is the owner's rule,
     * and {@code COALESCE} is the whole of it.
     */
    @Query("""
        SELECT v
        FROM OperationNormVersion v
        WHERE v.operation.id = :operationId
          AND v.archivedAt IS NULL
          AND v.id <> :excludedId
        ORDER BY COALESCE(v.normDate, CAST(v.createdAt AS date)) DESC, v.createdAt DESC, v.id DESC
        """)
    List<OperationNormVersion> findSuccessionCandidates(@Param("operationId") Long operationId,
                                                        @Param("excludedId") Long excludedId);
}
