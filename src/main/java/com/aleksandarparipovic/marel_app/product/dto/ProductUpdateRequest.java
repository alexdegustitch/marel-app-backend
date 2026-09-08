package com.aleksandarparipovic.marel_app.product.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Editing a product's catalogue placement and fields. Null means "leave it";
 * a blank string clears an optional text field. This is how an existing,
 * uncategorised product is filed under a type — the whole point of the wiring.
 *
 * <p>Name, code, description and active status are not touched here; this request
 * is scoped to the catalogue fields the hierarchy added.
 */
@Getter
@Setter
public class ProductUpdateRequest {

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
