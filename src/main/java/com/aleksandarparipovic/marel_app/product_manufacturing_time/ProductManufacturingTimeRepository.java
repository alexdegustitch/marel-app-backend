package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ProductManufacturingTimeRepository
        extends JpaRepository<ProductManufacturingTime, Long>,
                JpaSpecificationExecutor<ProductManufacturingTime> {

    List<ProductManufacturingTime> findByOperation_IdAndActiveTrue(Long operationId);

    List<ProductManufacturingTime> findByUser_IdAndActiveTrue(Long userId);

    @Query("""
        SELECT p FROM ProductManufacturingTime p
        WHERE p.operation.id = :operationId
          AND p.manufacturingDate = :date
          AND p.active = true
    """)
    List<ProductManufacturingTime> findByOperationIdAndDate(
            @Param("operationId") Long operationId,
            @Param("date") LocalDate date
    );

    @Query("""
        SELECT p FROM ProductManufacturingTime p
        WHERE p.operation.id = :operationId
          AND p.manufacturingDate BETWEEN :from AND :to
          AND p.active = true
        ORDER BY p.manufacturingDate DESC
    """)
    List<ProductManufacturingTime> findByOperationIdAndDateRange(
            @Param("operationId") Long operationId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}

