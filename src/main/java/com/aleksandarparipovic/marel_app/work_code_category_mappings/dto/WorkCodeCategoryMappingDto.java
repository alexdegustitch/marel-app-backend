package com.aleksandarparipovic.marel_app.work_code_category_mappings.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record WorkCodeCategoryMappingDto(
        Long id,
        Long sourceCategoryId,
        Long targetCategoryId,
        String mappingType,
        Boolean isActive,
        LocalDate validFrom,
        LocalDate validUntil,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime archivedAt
) {
}

