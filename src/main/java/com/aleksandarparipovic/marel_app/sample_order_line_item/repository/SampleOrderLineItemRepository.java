package com.aleksandarparipovic.marel_app.sample_order_line_item.repository;

import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SampleOrderLineItemRepository extends JpaRepository<SampleOrderLineItem, Long> {

    List<SampleOrderLineItem> findBySampleOrder_IdAndIsActiveIsTrueOrderByOrderLineAsc(Long sampleOrderId);

    /**
     * The live lines of several orders at once, with their products.
     *
     * <p>One query for a whole page rather than one per order. The product is
     * fetched with them because every caller reads its name, and leaving it lazy
     * turns a page of twenty orders into a query per line.
     */
    @Query("""
            select li from SampleOrderLineItem li
            join fetch li.product
            where li.sampleOrder.id in :orderIds
              and li.isActive = true
              and li.archivedAt is null
            order by li.sampleOrder.id, li.orderLine
            """)
    List<SampleOrderLineItem> findActiveWithProductByOrderIds(@Param("orderIds") List<Long> orderIds);

    /**
     * Every live sample order the product appears on. Same both-sides
     * filtering as the production-order query. Ordering comes from the caller
     * (the product page sorts server-side); {@code pattern} is a ready-made
     * lower-cased LIKE pattern over order name, or null for "all".
     */
    @Query("""
            select new com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow(
                so.id, so.name, so.status, so.creationDate, so.deadlineDate, li.quantity, li.catalogNo, li.note,
                cust.name)
            from SampleOrderLineItem li
            join li.sampleOrder so
            left join so.customer cust
            where li.product.id = :productId
              and li.isActive = true
              and li.archivedAt is null
              and so.isActive = true
              and so.archivedAt is null
              and (:pattern is null
                   or lower(so.name) like :pattern
                   or lower(li.catalogNo) like :pattern
                   or lower(cust.name) like :pattern)
            """)
    List<ProductSampleOrderRow> findOrderRowsByProductId(@Param("productId") Long productId,
                                                         @Param("pattern") String pattern,
                                                         Sort sort);

    /**
     * The same rows as {@link #findOrderRowsByProductId}, one PAGE at a time —
     * for the operation page, which shows ten sample orders and asks the server
     * for the next ten rather than shipping them all.
     */
    @Query(value = """
            select new com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow(
                so.id, so.name, so.status, so.creationDate, so.deadlineDate, li.quantity, li.catalogNo, li.note,
                cust.name)
            from SampleOrderLineItem li
            join li.sampleOrder so
            left join so.customer cust
            where li.product.id = :productId
              and li.isActive = true
              and li.archivedAt is null
              and so.isActive = true
              and so.archivedAt is null
              and (:pattern is null
                   or lower(so.name) like :pattern
                   or lower(li.catalogNo) like :pattern
                   or lower(cust.name) like :pattern)
            """,
            countQuery = """
            select count(li)
            from SampleOrderLineItem li
            join li.sampleOrder so
            left join so.customer cust
            where li.product.id = :productId
              and li.isActive = true
              and li.archivedAt is null
              and so.isActive = true
              and so.archivedAt is null
              and (:pattern is null
                   or lower(so.name) like :pattern
                   or lower(li.catalogNo) like :pattern
                   or lower(cust.name) like :pattern)
            """)
    Page<ProductSampleOrderRow> findOrderPageByProductId(@Param("productId") Long productId,
                                                         @Param("pattern") String pattern,
                                                         Pageable pageable);
}
