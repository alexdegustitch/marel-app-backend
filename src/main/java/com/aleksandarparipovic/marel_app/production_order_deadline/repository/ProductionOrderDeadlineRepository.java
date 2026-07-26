package com.aleksandarparipovic.marel_app.production_order_deadline.repository;

import com.aleksandarparipovic.marel_app.production_order_deadline.ProductionOrderDeadline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderDeadlineRepository extends JpaRepository<ProductionOrderDeadline, Long> {

    List<ProductionOrderDeadline> findByProductionOrder_IdOrderByDeadlineOrderAsc(Long productionOrderId);

    Optional<ProductionOrderDeadline> findByProductionOrder_IdAndIsActiveIsTrue(Long productionOrderId);

    List<ProductionOrderDeadline> findByProductionOrder_IdInOrderByDeadlineOrderAsc(List<Long> productionOrderIds);

    List<ProductionOrderDeadline> findAllByProductionOrder_IdAndIsActiveIsTrue(Long productionOrderId);
}
