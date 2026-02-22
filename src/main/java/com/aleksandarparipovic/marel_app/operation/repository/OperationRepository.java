package com.aleksandarparipovic.marel_app.operation.repository;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OperationRepository extends JpaRepository<Operation, Long>, JpaSpecificationExecutor<Operation>, OperationRepositoryCustom {

    List<Operation> findByProductIdInAndArchivedAtIsNull(List<Long> productIds);

    @Query("""
    select new com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow(
      o.id,
      p.id,
      o.opName,
      p.productName,
      o.minNorm,
      o.maxNorm,
      o.unitsPerProduct,
      o.normDate,
      (
        select count(o2.id)
        from Operation o2
        where o2.product = p
          and o2.archivedAt is null
      )
    )
    from Operation o
    join o.product p
    where o.archivedAt is null
      and p.archivedAt is null
      and o.id = :id
    """)
    Optional<OperationWithProductInfoRow> findOperationWithProductById(@Param("id") Long id);
}
