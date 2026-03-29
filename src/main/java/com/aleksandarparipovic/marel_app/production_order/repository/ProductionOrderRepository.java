package com.aleksandarparipovic.marel_app.production_order.repository;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionOrderRepository extends JpaRepository<ProductionOrder, Long>, JpaSpecificationExecutor<ProductionOrder> {

    List<ProductionOrder> findByIsActiveIsTrueOrderByNameAsc();
}
