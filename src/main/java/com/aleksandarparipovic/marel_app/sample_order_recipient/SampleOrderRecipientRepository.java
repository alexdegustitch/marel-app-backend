package com.aleksandarparipovic.marel_app.sample_order_recipient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SampleOrderRecipientRepository extends JpaRepository<SampleOrderRecipient, Long> {

    /** The send path and the snapshot view: active recipients of an order. */
    @Query("""
            select r from SampleOrderRecipient r
            left join fetch r.user
            left join fetch r.sourceMailingList
            where r.sampleOrder.id = :sampleOrderId
              and r.removedAt is null
            order by r.id
            """)
    List<SampleOrderRecipient> findActiveBySampleOrderId(@Param("sampleOrderId") Long sampleOrderId);

    Optional<SampleOrderRecipient> findBySampleOrder_IdAndRecipientEmailAndRemovedAtIsNull(
            Long sampleOrderId, String recipientEmail);

    /** Rows a given mailing list contributed and still owns — used when detaching. */
    List<SampleOrderRecipient> findBySampleOrder_IdAndSourceMailingList_IdAndRemovedAtIsNull(
            Long sampleOrderId, Long mailingListId);
}
