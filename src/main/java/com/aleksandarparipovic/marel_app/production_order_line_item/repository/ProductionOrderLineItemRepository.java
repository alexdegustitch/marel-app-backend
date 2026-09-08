package com.aleksandarparipovic.marel_app.production_order_line_item.repository;

import com.aleksandarparipovic.marel_app.product.dto.ProductProductionOrderRow;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionOrderLineItemRepository extends JpaRepository<ProductionOrderLineItem, Long> {

    List<ProductionOrderLineItem> findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(Long productionOrderId);

    List<ProductionOrderLineItem> findByProductionOrder_IdInAndIsActiveIsTrue(List<Long> productionOrderIds);

    /**
     * The live lines of several orders at once, with their products already
     * loaded.
     *
     * <p>The fetch is the point: a page of orders read line by line would ask
     * the database for the product name once per line. Callers must skip this
     * when the id list is empty — an empty IN is not a question the database
     * accepts.
     */
    @Query("""
            select li from ProductionOrderLineItem li
            join fetch li.product
            where li.productionOrder.id in :productionOrderIds
              and li.isActive = true
              and li.archivedAt is null
            order by li.lineOrder asc, li.id asc
            """)
    List<ProductionOrderLineItem> findActiveWithProductByOrderIds(@Param("productionOrderIds") List<Long> productionOrderIds);

    /**
     * Every live production order the product appears on.
     * Archived or deactivated lines and orders are excluded on both sides — a
     * removed line must not resurrect the order on the product's page.
     * Ordering comes from the caller (the product page sorts server-side);
     * {@code pattern} is a ready-made lower-cased LIKE pattern over the
     * order's code and name, or null for "all".
     */
    @Query("""
            select new com.aleksandarparipovic.marel_app.product.dto.ProductProductionOrderRow(
                po.id, po.code, po.name, po.status, po.orderDate, po.deliveryDeadline, li.quantity, li.note)
            from ProductionOrderLineItem li
            join li.productionOrder po
            where li.product.id = :productId
              and li.isActive = true
              and li.archivedAt is null
              and po.isActive = true
              and po.archivedAt is null
              and (:pattern is null
                   or lower(po.code) like :pattern
                   or lower(po.name) like :pattern)
            """)
    List<ProductProductionOrderRow> findOrderRowsByProductId(@Param("productId") Long productId,
                                                             @Param("pattern") String pattern,
                                                             Sort sort);
}
