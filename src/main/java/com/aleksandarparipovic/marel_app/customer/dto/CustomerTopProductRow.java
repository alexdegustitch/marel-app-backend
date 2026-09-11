package com.aleksandarparipovic.marel_app.customer.dto;

import java.time.LocalDate;

/**
 * One line of "what this customer orders most": a product, the total pieces
 * across their live production orders, and when it was last ordered. Summed at
 * read time from the order lines — never a stored counter, for the same reason
 * order progress is computed on read.
 */
public record CustomerTopProductRow(
        Long productId,
        String productName,
        String productCode,
        long totalQuantity,
        long orderCount,
        LocalDate lastOrderDate
) {
}
