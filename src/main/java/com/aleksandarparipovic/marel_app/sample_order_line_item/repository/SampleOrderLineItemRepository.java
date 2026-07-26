package com.aleksandarparipovic.marel_app.sample_order_line_item.repository;

import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SampleOrderLineItemRepository extends JpaRepository<SampleOrderLineItem, Long> {

    List<SampleOrderLineItem> findBySampleOrder_IdAndIsActiveIsTrueOrderByOrderLineAsc(Long sampleOrderId);
}
