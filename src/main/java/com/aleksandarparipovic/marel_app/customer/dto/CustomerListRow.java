package com.aleksandarparipovic.marel_app.customer.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One customer as the list page shows them: the card's basic facts plus the
 * figures a person scans the list by — how much work the customer has ordered
 * and how much of it is still open.
 *
 * <p>A superset of {@link CustomerDto}, so anything reading the old shape keeps
 * reading it. The counts cover live (non-archived) orders only; a delivered
 * order still counts toward the total, because "how many orders" is a question
 * about history, while {@code activeProductionOrderCount} is the question about
 * now.
 */
public record CustomerListRow(
        Long id,
        String code,
        String name,
        String taxId,
        String website,
        String email,
        String phone,
        Boolean active,
        OffsetDateTime archivedAt,
        /** Live production orders, delivered ones included. */
        long productionOrderCount,
        /** Live production orders still in status CREATED. */
        long activeProductionOrderCount,
        /** Live sample orders, closed ones included. */
        long sampleOrderCount,
        /** The newest orderDate among the live production orders; null when there are none. */
        LocalDate lastOrderDate
) {
}
