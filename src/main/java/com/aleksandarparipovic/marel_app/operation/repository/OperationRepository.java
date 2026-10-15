package com.aleksandarparipovic.marel_app.operation.repository;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.dto.OperationStatsRow;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductNameDto;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OperationRepository extends JpaRepository<Operation, Long>, JpaSpecificationExecutor<Operation>, OperationRepositoryCustom {

    List<Operation> findByProductIdInAndArchivedAtIsNull(List<Long> productIds);

    long countByProduct_IdAndArchivedAtIsNull(Long productId);

    @Query("""
select new com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductNameDto(
    o.id,
    o.opName,
    o.minNorm,
    o.maxNorm,
    o.normRequired,
    o.normDate,
    o.unitsPerProduct,
    p.id,
    p.productName,
    p.displayName,
    p.catalogNumber,
    wcc.id,
    o.temporary,
    o.standalone
)
from Operation o
left join o.product p
left join o.workCodeCategory wcc
where o.id = :id
""")
    Optional<OperationWithProductNameDto> findByIdWithProduct(@Param("id") Long id);


    List<Operation> findByProductIdAndArchivedAtIsNull(Long productId);

    /**
     * Operations that were archived BECAUSE their product was archived — the
     * ones a product restore is allowed to bring back. Operations archived on
     * their own stay archived.
     */
    List<Operation> findByProductIdAndArchivedByProductTrue(Long productId);

    /**
     * A product's live operations, searched and sorted for the product page.
     * {@code pattern} is a ready-made lower-cased LIKE pattern over the
     * operation's name and description, or null for "all"; ordering comes from
     * the caller.
     */
    @Query("""
            select o from Operation o
            where o.product.id = :productId
              and o.archivedAt is null
              and (:pattern is null
                   or lower(o.opName) like :pattern
                   or lower(o.description) like :pattern)
            """)
    List<Operation> searchByProduct(@Param("productId") Long productId,
                                    @Param("pattern") String pattern,
                                    Sort sort);

    /**
     * The board's figures in one query, over the same population the
     * search grid shows: live operations, whatever the state of their product.
     *
     * <p>The product is LEFT-joined so standalone operations stay in every
     * count; "without a norm" skips them on purpose — a standalone operation
     * never has a norm to miss.
     */
    @Query("""
            select new com.aleksandarparipovic.marel_app.operation.dto.OperationStatsRow(
                count(o),
                coalesce(sum(case when o.minNorm is null and o.standalone = false then 1 else 0 end), 0),
                coalesce(sum(case when o.workCodeCategory is null then 1 else 0 end), 0),
                count(distinct p.id),
                coalesce(sum(case when o.standalone = true then 1 else 0 end), 0)
            )
            from Operation o
            left join o.product p
            where o.archivedAt is null
            """)
    OperationStatsRow getStats();

    /**
     * The standalone (osnovne) operations a karton row may pick from — the
     * counterpart of {@link #findActiveOrArchivedAfterDate} for rows that
     * belong to no product: live ones, plus ones archived after the karton's
     * date so an old shift can still show what was picked back then.
     */
    @Query("""
            SELECT o
            FROM Operation o
            WHERE o.standalone = true
              AND (
                o.archivedAt IS NULL
                OR o.archivedAt > :dateTime
              )
            ORDER BY o.opName ASC
            """)
    List<Operation> findStandaloneActiveOrArchivedAfterDate(@Param("dateTime") OffsetDateTime dateTime);

    /**
     * The live standalone operation of this name, if there is one. At most one
     * can exist — uq_operations_standalone_op_name_ci is case-insensitive over
     * exactly the standalone rows — so an Optional tells the whole truth.
     * Used by {@code BreakLogWriter} to find "Pauza" without an id in Java.
     */
    @Query("""
            SELECT o FROM Operation o
            WHERE o.standalone = true
              AND lower(o.opName) = lower(:name)
              AND o.active = true
              AND o.archivedAt IS NULL
            """)
    Optional<Operation> findStandaloneByName(@Param("name") String name);

    /**
     * The live operations carrying a given work code category, by CODE.
     *
     * <p>Used to find the one operation that exists so a neradni dan has
     * something to hang a work log from — {@code work_logs.operation_id} is NOT
     * NULL, and ND is not work anybody performed on a product.
     *
     * <p>Returns a list rather than an Optional on purpose: nothing in the
     * database enforces that there is exactly one, so the caller checks and says
     * so plainly instead of silently taking whichever row came back first.
     */
    @Query("""
            select o from Operation o
            where o.workCodeCategory.categoryNo = :categoryNo
              and o.active = true
              and o.archivedAt is null
            order by o.id asc
            """)
    List<Operation> findActiveByWorkCodeCategoryNo(@Param("categoryNo") String categoryNo);

    @Query("""
    SELECT o
    FROM Operation o
    WHERE o.product.id = :productId
      AND (
        o.archivedAt IS NULL
        OR o.archivedAt > :dateTime
      )
""")
    List<Operation> findActiveOrArchivedAfterDate(
            @Param("productId") Long productId,
            @Param("dateTime") OffsetDateTime dateTime
    );

    /**
     * Operations whose name or description contains {@code q}, for the global
     * command-palette search. Case-insensitive, live operations only; the
     * product name rides along as the subtitle. The caller caps the page.
     */
    @Query("""
            select o.id as id,
                   o.opName as opName,
                   p.productName as productName
            from Operation o
            left join o.product p
            where o.archivedAt is null
              and (lower(o.opName) like lower(concat('%', :q, '%'))
                or lower(coalesce(o.description, '')) like lower(concat('%', :q, '%')))
            order by o.opName asc, o.id asc
            """)
    List<com.aleksandarparipovic.marel_app.search.dto.OperationSearchRow> searchTop(
            @Param("q") String q, org.springframework.data.domain.Pageable pageable);

    /**
     * Diacritic-folding, any-token match for Sparky's fuzzy resolver. Both sides
     * are folded — the column with Postgres {@code translate(lower(...))} (core
     * function, no extension), the query in Java — so a fuzzy "operacija 3" finds
     * "Operacija 3". When {@code productId} is non-null the search is scoped to
     * that product's operations (so "operacija 3" for a resolved product is that
     * product's Operacija 3); pass null for a global search. Live operations
     * only; capped at 25. The owning product rides along for ranking/scoping.
     */
    @Query(value = """
            SELECT o.id           AS id,
                   o.op_name      AS opName,
                   o.product_id   AS productId,
                   p.product_name AS productName
            FROM operations o
            LEFT JOIN products p ON p.id = o.product_id
            WHERE o.archived_at IS NULL
              AND (CAST(:productId AS bigint) IS NULL OR o.product_id = :productId)
              AND EXISTS (
                  SELECT 1
                  FROM regexp_split_to_table(trim(:folded), '\\s+') AS tok
                  WHERE tok <> ''
                    AND translate(lower(o.op_name), 'čćđšžČĆĐŠŽ', 'ccdszccdsz') LIKE '%' || tok || '%'
              )
            ORDER BY o.op_name ASC, o.id ASC
            LIMIT 25
            """, nativeQuery = true)
    List<com.aleksandarparipovic.marel_app.search.dto.OperationMatchRow> searchFolded(
            @Param("folded") String folded, @Param("productId") Long productId);

}
