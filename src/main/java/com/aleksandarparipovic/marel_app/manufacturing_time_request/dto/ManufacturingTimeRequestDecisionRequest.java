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
 * <p>For a completion, exactly one of the two payloads is used, chosen by the
 * request's own type — a CREATE request carries {@code manufacturingTime}, and
 * UPDATE/RECALCULATE carry {@code manufacturingTimeUpdate}. DEACTIVATE needs
 * neither. The client cannot pick which one applies; the stored request type does.
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
}
