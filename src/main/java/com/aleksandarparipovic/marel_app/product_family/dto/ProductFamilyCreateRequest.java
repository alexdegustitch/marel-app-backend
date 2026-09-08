package com.aleksandarparipovic.marel_app.product_family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Only the name is required. Everything else is presentation the family can be
 * given later.
 */
@Getter
@Setter
public class ProductFamilyCreateRequest {

    @NotBlank(message = "Naziv porodice je obavezan.")
    @Size(max = 255, message = "Naziv je predugačak.")
    private String name;

    private String description;

    /** Where the family sits in the list; defaults to 0 when absent. */
    private Integer sortOrder;
}
