package com.aleksandarparipovic.marel_app.sample_order_line_item_quantity.repository;

import com.aleksandarparipovic.marel_app.sample_order_line_item_quantity.SampleOrderLineItemQuantity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SampleOrderLineItemQuantityRepository extends JpaRepository<SampleOrderLineItemQuantity, Long> {

    List<SampleOrderLineItemQuantity> findBySampleOrderLineItem_IdOrderByOrderQuantityAsc(Long sampleOrderLineItemId);

    Optional<SampleOrderLineItemQuantity> findBySampleOrderLineItem_IdAndIsActiveIsTrue(Long sampleOrderLineItemId);
}
