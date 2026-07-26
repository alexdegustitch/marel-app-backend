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
        /** The record this request produced, once completed. */
        Long resultManufacturingTimeId,
        OffsetDateTime cancelledAt,
        OffsetDateTime createdAt
) {
}
