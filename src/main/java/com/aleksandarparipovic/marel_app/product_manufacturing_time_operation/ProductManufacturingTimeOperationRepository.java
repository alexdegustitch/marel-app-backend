package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductManufacturingTimeOperationRepository
        extends JpaRepository<ProductManufacturingTimeOperation, Long> {

    List<ProductManufacturingTimeOperation> findByProductManufacturingTime_IdAndActiveTrue(Long productManufacturingTimeId);
}

