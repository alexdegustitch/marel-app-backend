package com.aleksandarparipovic.marel_app.operation.repository;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductNameDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OperationRepository extends JpaRepository<Operation, Long>, JpaSpecificationExecutor<Operation>, OperationRepositoryCustom {

    List<Operation> findByProductIdInAndArchivedAtIsNull(List<Long> productIds);

    long countByProduct_IdAndArchivedAtIsNull(Long productId);

    @Query("""
select new com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductNameDto(
    o.id,
    o.opName,
    o.minNorm,
    o.maxNorm,
    o.normRequired,
    o.normDate,
    o.unitsPerProduct,
    p.id,
    p.productName,
    wcc.id
)
from Operation o
join o.product p
left join o.workCodeCategory wcc
where o.id = :id
""")
    Optional<OperationWithProductNameDto> findByIdWithProduct(@Param("id") Long id);


    List<Operation> findByProductIdAndArchivedAtIsNull(Long productId);

    @Query("""
    SELECT o
    FROM Operation o
    WHERE o.product.id = :productId
      AND (
        o.archivedAt IS NULL
        OR o.archivedAt > :dateTime
      )
""")
    List<Operation> findActiveOrArchivedAfterDate(
            @Param("productId") Long productId,
            @Param("dateTime") OffsetDateTime dateTime
    );

}
