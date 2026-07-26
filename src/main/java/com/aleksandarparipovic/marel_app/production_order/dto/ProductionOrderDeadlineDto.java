package com.aleksandarparipovic.marel_app.production_order.dto;

import java.time.LocalDate;

public record ProductionOrderDeadlineDto(
        Long id,
        Integer deadlineOrder,
        LocalDate deadlineDateFrom,
        LocalDate deadlineDateTo,
        Integer quantity,
        Boolean isActive
) {
}
