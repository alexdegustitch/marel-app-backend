package com.aleksandarparipovic.marel_app.image.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Null means "leave it". Setting {@code primary} true makes this the owner's
 * primary and clears the previous one; {@code sortOrder} moves it in the strip.
 */
@Getter
@Setter
public class ImageUpdateRequest {

    @Size(max = 255, message = "Opis slike je predugačak.")
    private String altText;

    private Boolean primary;

    private Integer sortOrder;
}
