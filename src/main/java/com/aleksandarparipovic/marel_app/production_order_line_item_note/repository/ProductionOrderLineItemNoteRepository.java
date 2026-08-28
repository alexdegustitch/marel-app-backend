package com.aleksandarparipovic.marel_app.production_order_line_item_note.repository;

import com.aleksandarparipovic.marel_app.production_order_line_item_note.ProductionOrderLineItemNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderLineItemNoteRepository extends JpaRepository<ProductionOrderLineItemNote, Long> {

    List<ProductionOrderLineItemNote> findByProductionOrderLineItem_IdOrderByOrderNoteAsc(Long productionOrderLineItemId);

    Optional<ProductionOrderLineItemNote> findByProductionOrderLineItem_IdAndIsActiveIsTrue(Long productionOrderLineItemId);

    /**
     * The live notes of several line items at once, in the order they were
     * written. Callers must skip this when the id list is empty.
     */
    @Query("""
            select n from ProductionOrderLineItemNote n
            where n.productionOrderLineItem.id in :lineItemIds
              and n.isActive = true
            order by n.orderNote asc, n.id asc
            """)
    List<ProductionOrderLineItemNote> findActiveByLineItemIds(@Param("lineItemIds") List<Long> lineItemIds);
}
