package com.aleksandarparipovic.marel_app.work_code_category_mappings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record WorkCodeCategoryMappingCreateRequest(
        @NotNull @Positive Long sourceCategoryId,
        @NotNull @Positive Long targetCategoryId,
        @NotBlank String mappingType,
        @NotNull LocalDate validFrom,
        LocalDate validUntil,
        String note
) {
}

