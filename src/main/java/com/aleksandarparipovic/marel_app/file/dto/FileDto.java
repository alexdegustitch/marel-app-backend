package com.aleksandarparipovic.marel_app.file.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * A stored file as the API returns it. The storage key is deliberately NOT
 * exposed — clients read the bytes through {@code downloadUrl}, never by
 * addressing the bucket.
 */
@Getter
@Builder
public class FileDto {

    private Long id;
    private String originalFilename;
    private String contentType;
    private Long sizeBytes;
    private OffsetDateTime createdAt;
    /** Where to fetch the bytes: the backend content endpoint for this file. */
    private String downloadUrl;
}
