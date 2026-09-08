package com.aleksandarparipovic.marel_app.product.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Editing a product from its detail page. Null means "leave it"; a blank
 * string clears an optional text field.
 *
 * <p>Originally scoped to the catalogue fields the hierarchy added; the product
 * detail page's per-field editing widened it to the base fields as well
 * (name, code, description, active). Name is the one field that cannot be
 * cleared — a provided blank name is refused, not treated as "leave it".
 */
@Getter
@Setter
public class ProductUpdateRequest {

    @Size(max = 255, message = "Naziv proizvoda je predugačak.")
    private String productName;

    @Size(max = 100, message = "Kod proizvoda je predugačak.")
    private String productCode;

    @Size(max = 1000, message = "Opis je predugačak.")
    private String description;

    private Boolean active;

    /** Null = leave it; 0 = clear the type (back to uncategorised); else the new type's id. */
    private Long productTypeId;

    @Size(max = 50, message = "Kataloški broj je predugačak.")
    private String catalogNumber;

    @Size(max = 50, message = "Podtip je predugačak.")
    private String subtype;

    @Size(max = 255, message = "Ime nadzornika je predugačko.")
    private String supervisorName;

    @Size(max = 255, message = "Prikazni naziv je predugačak.")
    private String displayName;
}
