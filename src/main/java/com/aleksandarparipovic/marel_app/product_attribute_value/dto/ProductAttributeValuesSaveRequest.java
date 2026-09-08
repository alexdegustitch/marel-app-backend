package com.aleksandarparipovic.marel_app.product_attribute_value.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * The full set of spec values for one product, saved in one call (a PUT: the
 * list REPLACES the product's values). An attribute absent from the list, or
 * present with a blank value, is cleared.
 */
@Getter
@Setter
public class ProductAttributeValuesSaveRequest {

    @NotNull
    @Valid
    private List<Item> values = new ArrayList<>();

    @Getter
    @Setter
    public static class Item {

        @NotNull(message = "Atribut je obavezan.")
        private Long attributeId;

        /** Blank or null clears the value for this attribute. */
        @Size(max = 255, message = "Vrednost je predugačka.")
        private String value;
    }
}
