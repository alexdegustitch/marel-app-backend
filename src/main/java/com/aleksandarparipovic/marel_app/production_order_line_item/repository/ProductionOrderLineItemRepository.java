package com.aleksandarparipovic.marel_app.production_order_line_item.repository;

import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionOrderLineItemRepository extends JpaRepository<ProductionOrderLineItem, Long> {

    List<ProductionOrderLineItem> findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(Long productionOrderId);

    List<ProductionOrderLineItem> findByProductionOrder_IdInAndIsActiveIsTrue(List<Long> productionOrderIds);
}
