package com.aleksandarparipovic.marel_app.image.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Attach an already-uploaded file (see POST /api/files) to an owner as an image.
 * Two-step by design: upload returns a fileId, this links it. The first image
 * attached to an owner becomes its primary automatically unless said otherwise.
 */
@Getter
@Setter
public class ImageAttachRequest {

    @NotNull(message = "fileId je obavezan.")
    private Long fileId;

    @Size(max = 255, message = "Opis slike je predugačak.")
    private String altText;

    /** Make this the owner's primary image. Defaults to true for the first one. */
    private Boolean primary;
}
