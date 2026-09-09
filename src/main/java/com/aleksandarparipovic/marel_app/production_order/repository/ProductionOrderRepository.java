package com.aleksandarparipovic.marel_app.production_order.repository;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.search.dto.OrderSearchRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionOrderRepository extends JpaRepository<ProductionOrder, Long>, JpaSpecificationExecutor<ProductionOrder> {

    List<ProductionOrder> findByIsActiveIsTrueOrderByNameAsc();

    /**
     * Production orders whose name or code contains {@code q}, for the global
     * command-palette search. Case-insensitive, non-archived orders only, exact
     * code matches first; the customer name rides along as the subtitle. The
     * caller caps the page.
     */
    @Query("""
            select o.id as id,
                   o.name as name,
                   o.code as code,
                   c.name as customerName
            from ProductionOrder o
            left join o.customer c
            where o.archivedAt is null
              and (lower(o.name) like lower(concat('%', :q, '%'))
                or lower(o.code) like lower(concat('%', :q, '%'))
                or lower(coalesce(o.note, '')) like lower(concat('%', :q, '%')))
            order by case when lower(o.code) = lower(:q) then 0 else 1 end,
                     o.name asc, o.id asc
            """)
    List<OrderSearchRow> searchTop(@Param("q") String q, Pageable pageable);

    /**
     * Diacritic-folding, any-token match for Sparky's fuzzy resolver. Both sides
     * are folded — the columns with Postgres {@code translate(lower(...))} (core
     * function, no extension), the query in Java — so a code or name typed
     * loosely still resolves. A row matches when its folded name or code contains
     * ANY whitespace token of {@code folded}; the caller ranks and caps.
     * Non-archived orders only; capped at 25.
     */
    @Query(value = """
            SELECT o.id   AS id,
                   o.name AS name,
                   o.code AS code,
                   c.name AS customerName
            FROM production_orders o
            LEFT JOIN customers c ON c.id = o.customer_id
            WHERE o.archived_at IS NULL
              AND EXISTS (
                  SELECT 1
                  FROM regexp_split_to_table(trim(:folded), '\\s+') AS tok
                  WHERE tok <> ''
                    AND (translate(lower(o.name), 'čćđšžČĆĐŠŽ', 'ccdszccdsz') LIKE '%' || tok || '%'
                      OR translate(lower(coalesce(o.code, '')), 'čćđšžČĆĐŠŽ', 'ccdszccdsz') LIKE '%' || tok || '%')
              )
            ORDER BY o.name ASC, o.id ASC
            LIMIT 25
            """, nativeQuery = true)
    List<OrderSearchRow> searchFolded(@Param("folded") String folded);
}
