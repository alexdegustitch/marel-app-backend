package com.aleksandarparipovic.marel_app.compensation_scheme.dto;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;

public record CompensationSchemeDto(
        Long id,
        String code,
        String name,
        Boolean allowUnmappedCategories,
        String note
) {
    public static CompensationSchemeDto from(CompensationScheme scheme) {
        return new CompensationSchemeDto(
                scheme.getId(),
                scheme.getCode(),
                scheme.getName(),
                scheme.getAllowUnmappedCategories(),
                scheme.getNote());
    }
}
