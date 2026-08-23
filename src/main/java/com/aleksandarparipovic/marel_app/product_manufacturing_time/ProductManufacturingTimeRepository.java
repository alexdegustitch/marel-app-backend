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

    /**
     * Every active record that ANSWERS a request, whoever made it.
     *
     * <p>Not filtered by user on purpose: a manufacturing time produced through
     * the request workflow is the company's answer to somebody's ask, not the
     * processor's personal document. The per-user list next to it stays what it
     * is — a private working list.
     *
     * <p>Keyed off the request's own {@code result_manufacturing_time_id} rather
     * than {@code source_request_id}, so a record made outside the workflow and
     * later attached to a request counts too — it is answering one.
     */
    @org.springframework.data.jpa.repository.Query("""
            select distinct p
            from ProductManufacturingTime p
            where p.active = true
              and exists (
                  select 1
                  from ManufacturingTimeRequest r
                  where r.resultManufacturingTime.id = p.id
              )
            order by p.dateOfIssue desc
            """)
    java.util.List<ProductManufacturingTime> findAnsweringRequests();
}
