package com.aleksandarparipovic.marel_app.sample_order_line_item_note.repository;

import com.aleksandarparipovic.marel_app.sample_order_line_item_note.SampleOrderLineItemNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SampleOrderLineItemNoteRepository extends JpaRepository<SampleOrderLineItemNote, Long> {

    List<SampleOrderLineItemNote> findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(Long sampleOrderLineItemId);

    Optional<SampleOrderLineItemNote> findBySampleOrderLineItem_IdAndIsActiveIsTrue(Long sampleOrderLineItemId);
}
