package com.aleksandarparipovic.marel_app.image.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * An attached image as the API returns it: which stored file, how to fetch it,
 * and its role for the owner (primary, order).
 */
@Getter
@Builder
public class ImageDto {

    private Long id;
    private Long fileId;
    private String downloadUrl;
    private String originalFilename;
    private String contentType;
    private String altText;
    private Boolean primary;
    private Integer sortOrder;
}
