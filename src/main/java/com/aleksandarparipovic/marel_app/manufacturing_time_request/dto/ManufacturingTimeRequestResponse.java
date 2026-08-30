package com.aleksandarparipovic.marel_app.manufacturing_time_request.dto;

import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequestStatus;
import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequestType;

import java.time.OffsetDateTime;

public record ManufacturingTimeRequestResponse(
        Long id,
        Long productId,
        String productName,
        ManufacturingTimeRequestType requestType,
        String description,
        ManufacturingTimeRequestStatus status,
        Long createdByUserId,
        String createdByName,
        Long assignedToUserId,
        String assignedToName,
        Long processedByUserId,
        String processedByName,
        OffsetDateTime processedAt,
        String decisionNote,
        Long targetManufacturingTimeId,
        /** The production-order line the request was raised on; NULL if standalone. */
        Long productionOrderLineItemId,
        Long productionOrderId,
        /** Order code and name, so the queue shows the occasion without a second call. */
        String productionOrderCode,
        String productionOrderName,
        /** The line's own description, which may name a variant the product does not. */
        String productionOrderLineDescription,
        /**
         * The same five for a sample order, all null together when the request
         * came from a production line or from nowhere. Two sets of fields rather
         * than one pair plus a kind flag: a reader of this record should be able
         * to see WHICH kind of order is waiting without decoding a discriminator.
         */
        Long sampleOrderLineItemId,
        Long sampleOrderId,
        String sampleOrderCode,
        String sampleOrderName,
        String sampleOrderLineDescription,
        /**
         * The record that answers this request, once completed. It may answer
         * other requests too — one manufacturing time settles everyone who asked
         * for the same product's time.
         */
        Long resultManufacturingTimeId,
        /**
         * The two numbers the answer is actually read for: how long one piece
         * takes, and how many fit in an hour. The record's name and issue date
         * are deliberately absent — neither the requester nor the processor
         * reads a request row to learn what a record is called.
         */
        Integer resultManufacturingTimeSeconds,
        java.math.BigDecimal resultProductsPerHour,
        OffsetDateTime cancelledAt,
        OffsetDateTime createdAt
) {
}
