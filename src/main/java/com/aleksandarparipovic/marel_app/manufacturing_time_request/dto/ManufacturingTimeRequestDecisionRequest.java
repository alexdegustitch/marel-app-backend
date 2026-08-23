package com.aleksandarparipovic.marel_app.manufacturing_time_request.dto;

import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * What a processor sends when completing or declining a request.
 *
 * <p>For a completion, the request's own TYPE chooses the payload — a CREATE
 * request carries {@code manufacturingTime}, UPDATE/RECALCULATE carry
 * {@code manufacturingTimeUpdate}, and DEACTIVATE needs neither. The client
 * cannot pick which one applies.
 *
 * <p>{@code existingManufacturingTimeId} is the one exception the CLIENT does
 * choose: a CREATE request may be answered by a record that already exists
 * instead of by a new one. It is the only field here that can settle a request
 * without producing anything.
 */
@Getter
@Setter
public class ManufacturingTimeRequestDecisionRequest {

    @Size(max = 2000, message = "Napomena može imati najviše 2000 karaktera")
    private String decisionNote;

    @Valid
    private ProductManufacturingTimeCreateRequest manufacturingTime;

    @Valid
    private ProductManufacturingTimeUpdateRequest manufacturingTimeUpdate;

    /**
     * Answer a CREATE request with a manufacturing time that already exists.
     *
     * <p>The record may already answer other requests — that is the point, one
     * record settles everyone who asked for the same product's time. Refused for
     * the other request types, where the record to act on is already named by
     * the request itself.
     */
    private Long existingManufacturingTimeId;
}
