package com.aleksandarparipovic.marel_app.product.dto;

import java.time.LocalDate;

/**
 * One sample order the product appears on. Sample orders carry no code, and
 * their status is a free-form string on the entity, so both are served as the
 * database holds them.
 */
public record ProductSampleOrderRow(
        Long orderId,
        String name,
        String status,
        LocalDate creationDate,
        LocalDate deadlineDate,
        Integer quantity,
        String catalogNo,
        String lineNote
) {
}
