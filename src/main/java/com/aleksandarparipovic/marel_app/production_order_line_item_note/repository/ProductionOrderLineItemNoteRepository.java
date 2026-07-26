package com.aleksandarparipovic.marel_app.production_order_line_item_note.repository;

import com.aleksandarparipovic.marel_app.production_order_line_item_note.ProductionOrderLineItemNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderLineItemNoteRepository extends JpaRepository<ProductionOrderLineItemNote, Long> {

    List<ProductionOrderLineItemNote> findByProductionOrderLineItem_IdOrderByOrderNoteAsc(Long productionOrderLineItemId);

    Optional<ProductionOrderLineItemNote> findByProductionOrderLineItem_IdAndIsActiveIsTrue(Long productionOrderLineItemId);
}
