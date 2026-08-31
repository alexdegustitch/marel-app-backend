package com.aleksandarparipovic.marel_app.sample_order.dto;

import java.time.LocalDate;
import java.util.List;

public record SampleOrderDetailDto(
        Long id,
        String code,
        String name,
        String note,
        String status,
        LocalDate creationDate,
        LocalDate deadlineDate,
        String deadlineNote,
        /**
         * The account responsible, so the name can be a link to their page.
         * Null wherever userFullName is: an order may have no author recorded.
         */
        Long userId,
        String userFullName,
        /** Who closed it, and when the order says it is closed. */
        Long closedByUserId,
        String closedByName,
        /** Null when the samples are for nobody outside. */
        Long customerId,
        String customerName,
        List<SampleOrderLineItemDto> lineItems
) {
}
