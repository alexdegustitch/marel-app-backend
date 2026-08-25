package com.aleksandarparipovic.marel_app.production_order.dto;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;

import java.time.LocalDate;
import java.util.List;

public record ProductionOrderDetailDto(
        Long id,
        String code,
        String name,
        String note,
        Boolean testingRequired,
        ProductionOrderStatus status,
        LocalDate creationDate,
        LocalDate orderDate,
        String deliveryDeadline,
        Boolean isHighPriority,
        Boolean isAnnounced,
        Boolean hasSuccessiveDeliveries,
        /**
         * The account responsible, so the name can be a link to their page.
         * Null wherever userFullName is: an order may have no author recorded.
         */
        Long userId,
        String userFullName,
        /** Null when the order is internal. */
        Long customerId,
        String customerName,
        List<ProductionOrderDeadlineDto> deadlines,
        List<ProductionOrderLineItemDto> lineItems
) {
}
