package com.aleksandarparipovic.marel_app.manufacturing_time_request.dto;

import com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Everything a requester may supply. Status, creator, timestamps and assignment
 * are all derived server-side.
 */
@Getter
@Setter
public class ManufacturingTimeRequestCreateRequest {

    @NotNull(message = "Proizvod je obavezan")
    private Long productId;

    @NotNull(message = "Tip zahteva je obavezan")
    private ManufacturingTimeRequestType requestType;

    @NotBlank(message = "Opis je obavezan")
    @Size(max = 2000, message = "Opis može imati najviše 2000 karaktera")
    private String description;

    /** Required for every type except CREATE; validated in the service. */
    private Long targetManufacturingTimeId;
}
