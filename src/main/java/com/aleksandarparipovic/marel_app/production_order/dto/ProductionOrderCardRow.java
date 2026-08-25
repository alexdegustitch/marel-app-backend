package com.aleksandarparipovic.marel_app.production_order.dto;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;

import java.time.LocalDate;
import java.util.List;

public record ProductionOrderCardRow(
        Long id,
        String code,
        String name,
        String note,
        Boolean testingRequired,
        ProductionOrderStatus status,
        String deliveryDeadline,
        Boolean isHighPriority,
        Boolean isAnnounced,
        Boolean hasSuccessiveDeliveries,
        /** Null when the order is internal. */
        Long customerId,
        String customerName,
        LocalDate effectiveDeadlineDate,
        Boolean effectiveDeadlineFromLineItem,
        List<ProductionOrderDeadlineDto> deadlines
) {
}
