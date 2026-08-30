package com.aleksandarparipovic.marel_app.sample_order_line_item_quantity.repository;

import com.aleksandarparipovic.marel_app.sample_order_line_item_quantity.SampleOrderLineItemQuantity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SampleOrderLineItemQuantityRepository extends JpaRepository<SampleOrderLineItemQuantity, Long> {

    List<SampleOrderLineItemQuantity> findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(Long sampleOrderLineItemId);

    Optional<SampleOrderLineItemQuantity> findBySampleOrderLineItem_IdAndIsActiveIsTrue(Long sampleOrderLineItemId);

    /** The live quantity of several lines at once — one query for a whole page. */
    @Query("""
            select q from SampleOrderLineItemQuantity q
            where q.sampleOrderLineItem.id in :lineItemIds
              and q.isActive = true
            order by q.sampleOrderLineItem.id, q.orderQuantity
            """)
    List<SampleOrderLineItemQuantity> findActiveByLineItemIds(@Param("lineItemIds") List<Long> lineItemIds);
}
