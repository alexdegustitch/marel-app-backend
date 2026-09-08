package com.aleksandarparipovic.marel_app.product_type.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Null means "leave it". A type may be moved to another family by sending a new
 * familyId; the code's uniqueness is then re-checked against that family.
 */
@Getter
@Setter
public class ProductTypeUpdateRequest {

    private Long familyId;

    @Size(max = 255, message = "Naziv je predugačak.")
    private String name;

    @Size(max = 50, message = "Kod je predugačak.")
    private String code;

    private String description;

    private String note;

    private String standard;

    private Integer sortOrder;

    private Boolean active;
}
