package com.aleksandarparipovic.marel_app.product_type.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ProductTypeDto {

    private Long id;
    private Long familyId;
    private String familyName;
    private String name;
    private String code;
    private String description;
    private String note;
    private Integer sortOrder;
    private Boolean active;
    /** When the type was deactivated; null while active. */
    private OffsetDateTime archivedAt;
}
