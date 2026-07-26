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

    List<ProductManufacturingTime> findByProduct_IdAndActiveTrue(Long productId);

    List<ProductManufacturingTime> findByUser_IdAndActiveTrueOrderByDateOfIssueDesc(Long userId);

    @Query("""
        SELECT p FROM ProductManufacturingTime p
        WHERE p.product.id = :productId
          AND p.dateOfIssue BETWEEN :from AND :to
          AND p.active = true
        ORDER BY p.dateOfIssue DESC
    """)
    List<ProductManufacturingTime> findByProductIdAndDateRange(
            @Param("productId") Long productId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    java.util.Optional<ProductManufacturingTime> findBySourceRequest_Id(Long sourceRequestId);
}
