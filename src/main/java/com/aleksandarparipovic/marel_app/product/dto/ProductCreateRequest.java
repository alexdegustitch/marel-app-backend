package com.aleksandarparipovic.marel_app.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductCreateRequest {

    @NotBlank(message = "Product name is required")
    @Size(max = 255, message = "Product name is too long")
    private String productName;

    @Size(max = 100, message = "Product code is too long")
    private String productCode;

    @Size(max = 1000, message = "Description is too long")
    private String description;

    // Catalogue placement + fields. All optional — a product can start uncategorised.
    private Long productTypeId;

    @Size(max = 50, message = "Kataloški broj je predugačak.")
    private String catalogNumber;

    @Size(max = 50, message = "Podtip je predugačak.")
    private String subtype;

    @Size(max = 255, message = "Ime nadzornika je predugačko.")
    private String supervisorName;

    @Size(max = 255, message = "Prikazni naziv je predugačak.")
    private String displayName;

    /** TRUE marks an INTERNI proizvod — never allowed on an order line item. */
    private Boolean virtual;
}
