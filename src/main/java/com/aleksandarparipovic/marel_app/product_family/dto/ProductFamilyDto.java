package com.aleksandarparipovic.marel_app.product_family.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ProductFamilyDto {

    private Long id;
    private String name;
    private String description;
    private Integer sortOrder;
    private Boolean active;
    /** When the family was deactivated; null while active. */
    private OffsetDateTime archivedAt;
}
