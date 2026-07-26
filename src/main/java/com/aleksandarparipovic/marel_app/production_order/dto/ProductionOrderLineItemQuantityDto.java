package com.aleksandarparipovic.marel_app.production_order.dto;

import java.time.LocalDate;

public record ProductionOrderLineItemQuantityDto(
        Long id,
        Integer orderQuantity,
        Integer quantity,
        LocalDate deliveryDeadline,
        Boolean isActive
) {
}
