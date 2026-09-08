package com.aleksandarparipovic.marel_app.product_type.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A type must know its family, its name and its code. The rest is optional.
 */
@Getter
@Setter
public class ProductTypeCreateRequest {

    @NotNull(message = "Porodica je obavezna.")
    private Long familyId;

    @NotBlank(message = "Naziv tipa je obavezan.")
    @Size(max = 255, message = "Naziv je predugačak.")
    private String name;

    @NotBlank(message = "Kod tipa je obavezan.")
    @Size(max = 50, message = "Kod je predugačak.")
    private String code;

    private String description;

    private String note;

    /** Where the type sits in its family's list; defaults to 0 when absent. */
    private Integer sortOrder;
}
