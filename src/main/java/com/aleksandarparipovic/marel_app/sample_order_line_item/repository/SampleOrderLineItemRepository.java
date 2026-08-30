package com.aleksandarparipovic.marel_app.sample_order_line_item.repository;

import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
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
     * Every live sample order the product appears on, newest first. Same
     * both-sides filtering as the production-order query.
     */
    @Query("""
            select new com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow(
                so.id, so.name, so.status, so.creationDate, so.deadlineDate, li.quantity, li.catalogNo, li.note)
            from SampleOrderLineItem li
            join li.sampleOrder so
            where li.product.id = :productId
              and li.isActive = true
              and li.archivedAt is null
              and so.isActive = true
              and so.archivedAt is null
            order by so.creationDate desc nulls last, so.id desc
            """)
    List<ProductSampleOrderRow> findOrderRowsByProductId(@Param("productId") Long productId);
}
