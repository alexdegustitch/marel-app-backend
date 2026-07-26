package com.aleksandarparipovic.marel_app.production_order_line_item_quantity.repository;

import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.ProductionOrderLineItemQuantity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderLineItemQuantityRepository extends JpaRepository<ProductionOrderLineItemQuantity, Long> {

    List<ProductionOrderLineItemQuantity> findByProductionOrderLineItem_IdOrderByOrderQuantityAsc(Long productionOrderLineItemId);

    Optional<ProductionOrderLineItemQuantity> findByProductionOrderLineItem_IdAndIsActiveIsTrue(Long productionOrderLineItemId);

    List<ProductionOrderLineItemQuantity> findByProductionOrderLineItem_IdInAndIsActiveIsTrue(List<Long> productionOrderLineItemIds);
}
