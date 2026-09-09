package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductManufacturingTimeOperationRepository
        extends JpaRepository<ProductManufacturingTimeOperation, Long> {

    List<ProductManufacturingTimeOperation> findByProductManufacturingTime_IdAndActiveTrue(Long productManufacturingTimeId);

    /**
     * The active lines of a whole page of records in one query, ordered so each
     * record's lines come back in the order they were written.
     */
    List<ProductManufacturingTimeOperation> findByProductManufacturingTime_IdInAndActiveTrueOrderByIdAsc(
            java.util.Collection<Long> productManufacturingTimeIds);

    @Modifying
    @Query("UPDATE ProductManufacturingTimeOperation o SET o.active = false WHERE o.productManufacturingTime.id = :pmtId AND o.active = true")
    void deactivateAllByProductManufacturingTimeId(@Param("pmtId") Long pmtId);
}

