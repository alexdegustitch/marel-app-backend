package com.aleksandarparipovic.marel_app.product_family.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Null means "leave it". A blank description clears it; the name cannot be
 * blanked, only changed.
 */
@Getter
@Setter
public class ProductFamilyUpdateRequest {

    @Size(max = 255, message = "Naziv je predugačak.")
    private String name;

    private String description;

    private Integer sortOrder;

    private Boolean active;
}
