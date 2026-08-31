package com.aleksandarparipovic.marel_app.sample_order_line_item_note.repository;

import com.aleksandarparipovic.marel_app.sample_order_line_item_note.SampleOrderLineItemNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SampleOrderLineItemNoteRepository extends JpaRepository<SampleOrderLineItemNote, Long> {

    List<SampleOrderLineItemNote> findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(Long sampleOrderLineItemId);

    Optional<SampleOrderLineItemNote> findBySampleOrderLineItem_IdAndIsActiveIsTrue(Long sampleOrderLineItemId);

    /** The live notes of several lines at once — one query for a whole page. */
    @Query("""
            select n from SampleOrderLineItemNote n
            where n.sampleOrderLineItem.id in :lineItemIds
              and n.isActive = true
            order by n.sampleOrderLineItem.id, n.orderQuantity
            """)
    List<SampleOrderLineItemNote> findActiveByLineItemIds(@Param("lineItemIds") List<Long> lineItemIds);
}
