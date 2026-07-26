package com.aleksandarparipovic.marel_app.production_order_recipient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderRecipientRepository
        extends JpaRepository<ProductionOrderRecipient, Long> {

    /** The send path and the snapshot view: active recipients of an order. */
    @Query("""
            select r from ProductionOrderRecipient r
            left join fetch r.user
            left join fetch r.sourceMailingList
            where r.productionOrder.id = :productionOrderId
              and r.removedAt is null
            order by r.id
            """)
    List<ProductionOrderRecipient> findActiveByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId);

    Optional<ProductionOrderRecipient> findByProductionOrder_IdAndRecipientEmailAndRemovedAtIsNull(
            Long productionOrderId, String recipientEmail);

    /** Rows a given mailing list contributed and still owns — used when detaching. */
    List<ProductionOrderRecipient>
        findByProductionOrder_IdAndSourceMailingList_IdAndRemovedAtIsNull(
            Long productionOrderId, Long mailingListId);
}
