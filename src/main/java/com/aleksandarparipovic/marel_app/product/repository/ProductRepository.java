package com.aleksandarparipovic.marel_app.product.repository;


import com.aleksandarparipovic.marel_app.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository
        extends JpaRepository<Product, Long>,
        JpaSpecificationExecutor<Product>,
        ProductRepositoryCustom {

    List<Product> findByArchivedAtIsNullOrderByProductNameAsc();

    /**
     * Options for a lazy product dropdown: the typed fragment matched against
     * every part of the shown name (name, override, subtype) plus the product
     * code, live products only, alphabetically. The caller caps the page — a
     * dropdown never needs the whole catalogue at once.
     */
    @Query("""
            select p from Product p
            where p.archivedAt is null
              and (:q is null
                   or lower(p.productName) like :q
                   or lower(coalesce(p.displayName, '')) like :q
                   or lower(coalesce(p.subtype, '')) like :q
                   or lower(coalesce(p.productCode, '')) like :q)
            order by p.productName asc, p.id asc
            """)
    List<Product> searchOptions(@Param("q") String q, org.springframework.data.domain.Pageable pageable);

    long countByArchivedAtIsNull();

    long countByArchivedAtIsNullAndActiveTrue();

    /** Live products that have not a single live operation attached. */
    @Query("""
            select count(p) from Product p
            where p.archivedAt is null
              and not exists (
                  select o.id from Operation o
                  where o.product = p and o.archivedAt is null
              )
            """)
    long countWithoutLiveOperations();

    /** Live operations on live products — the catalogue's operation total. */
    @Query("""
            select count(o) from Operation o
            where o.archivedAt is null and o.product.archivedAt is null
            """)
    long countLiveOperations();

    Optional<Product> findByIdAndArchivedAtIsNull(Long id);

    boolean existsByProductNameIgnoreCaseAndArchivedAtIsNull(String productName);

    boolean existsByProductCodeIgnoreCaseAndArchivedAtIsNull(String productCode);

    /*
     * "Does another live product already carry this catalogue number" — excludes
     * an id so an update that leaves the number alone does not find itself. The
     * database has the last word (uq_products_catalog_number_ci is a partial
     * unique index); this exists so the refusal reads as a sentence.
     */
    @Query("""
            select count(p) > 0 from Product p
            where p.archivedAt is null
              and lower(p.catalogNumber) = lower(:catalogNumber)
              and (:excludeId is null or p.id <> :excludeId)
            """)
    boolean catalogNumberTakenByAnother(@Param("catalogNumber") String catalogNumber,
                                        @Param("excludeId") Long excludeId);

    /** "Does another live product already carry this name" — for renames. */
    @Query("""
            select count(p) > 0 from Product p
            where p.archivedAt is null
              and lower(p.productName) = lower(:productName)
              and (:excludeId is null or p.id <> :excludeId)
            """)
    boolean productNameTakenByAnother(@Param("productName") String productName,
                                      @Param("excludeId") Long excludeId);

    /** "Does another live product already carry this code" — for code edits. */
    @Query("""
            select count(p) > 0 from Product p
            where p.archivedAt is null
              and lower(p.productCode) = lower(:productCode)
              and (:excludeId is null or p.id <> :excludeId)
            """)
    boolean productCodeTakenByAnother(@Param("productCode") String productCode,
                                      @Param("excludeId") Long excludeId);

    /**
     * Products whose name or code contains {@code q}, for the global
     * command-palette search. Case-insensitive, live products only, exact code
     * matches first; the caller caps the page.
     */
    @Query("""
            select p.id as id,
                   p.productName as productName,
                   p.productCode as productCode
            from Product p
            where p.archivedAt is null
              and (lower(p.productName) like lower(concat('%', :q, '%'))
                or lower(coalesce(p.productCode, '')) like lower(concat('%', :q, '%'))
                or lower(coalesce(p.description, '')) like lower(concat('%', :q, '%')))
            order by case when lower(coalesce(p.productCode, '')) = lower(:q) then 0 else 1 end,
                     p.productName asc, p.id asc
            """)
    List<com.aleksandarparipovic.marel_app.search.dto.ProductSearchRow> searchTop(
            @Param("q") String q, org.springframework.data.domain.Pageable pageable);

    /**
     * Diacritic-folding, any-token match for Sparky's fuzzy resolver. Both sides
     * are folded — the columns with Postgres {@code translate(lower(...))} (core
     * function, no extension), the query in Java before it is passed in — so
     * "kuciste pumpa" reaches "Kućište pumpe". A row matches when its folded name
     * or code contains ANY whitespace token of {@code folded}; the caller ranks
     * and caps. Live products only; capped at 25.
     */
    @Query(value = """
            SELECT p.id           AS id,
                   p.product_name AS productName,
                   p.product_code AS productCode
            FROM products p
            WHERE p.archived_at IS NULL
              AND EXISTS (
                  SELECT 1
                  FROM regexp_split_to_table(trim(:folded), '\\s+') AS tok
                  WHERE tok <> ''
                    AND (translate(lower(p.product_name), 'čćđšžČĆĐŠŽ', 'ccdszccdsz') LIKE '%' || tok || '%'
                      OR translate(lower(coalesce(p.product_code, '')), 'čćđšžČĆĐŠŽ', 'ccdszccdsz') LIKE '%' || tok || '%')
              )
            ORDER BY p.product_name ASC, p.id ASC
            LIMIT 25
            """, nativeQuery = true)
    List<com.aleksandarparipovic.marel_app.search.dto.ProductSearchRow> searchFolded(
            @Param("folded") String folded);

}
