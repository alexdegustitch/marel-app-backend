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
        String userFullName,
        List<ProductionOrderDeadlineDto> deadlines,
        List<ProductionOrderLineItemDto> lineItems
) {
}
