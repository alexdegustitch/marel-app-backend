package com.aleksandarparipovic.marel_app.compensation_scheme.dto;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;

import java.time.OffsetDateTime;

public record CompensationSchemeDto(
        Long id,
        String code,
        String name,
        Boolean allowUnmappedCategories,
        /**
         * Whether work under this scheme earns a performance bonus. The create
         * form disables the bonus category on this flag rather than on the code,
         * so a scheme added tomorrow behaves correctly with no UI change.
         */
        Boolean allowsPerformanceBonus,
        String note,
        Boolean isActive,
        OffsetDateTime archivedAt
) {
    public static CompensationSchemeDto from(CompensationScheme scheme) {
        return new CompensationSchemeDto(
                scheme.getId(),
                scheme.getCode(),
                scheme.getName(),
                scheme.getAllowUnmappedCategories(),
                scheme.getAllowsPerformanceBonus(),
                scheme.getNote(),
                scheme.getIsActive(),
                scheme.getArchivedAt());
    }
}
