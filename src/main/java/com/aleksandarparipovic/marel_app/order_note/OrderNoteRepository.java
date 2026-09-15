package com.aleksandarparipovic.marel_app.order_note;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the wall. Filtering, sorting and searching all go through the
 * {@link JpaSpecificationExecutor} half, driven by {@link OrderNoteSpecifications}
 * — the service never pages a list in memory.
 */
@Repository
public interface OrderNoteRepository
        extends JpaRepository<OrderNote, Long>, JpaSpecificationExecutor<OrderNote> {

    /** The live-note count behind an order's "Beleške (N)" badge. */
    long countByProductionOrder_IdAndIsActiveIsTrue(Long productionOrderId);

    long countBySampleOrder_IdAndIsActiveIsTrue(Long sampleOrderId);
}
