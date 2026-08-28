package com.aleksandarparipovic.marel_app.production_order_email_thread;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductionOrderEmailThreadRepository
        extends JpaRepository<ProductionOrderEmailThread, Long> {

    Optional<ProductionOrderEmailThread> findByProductionOrder_Id(Long productionOrderId);
}
