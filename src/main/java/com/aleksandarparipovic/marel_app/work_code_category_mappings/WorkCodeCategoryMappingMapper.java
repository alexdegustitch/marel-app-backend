package com.aleksandarparipovic.marel_app.work_code_category_mappings;

import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingCreateRequest;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingDto;
import org.springframework.stereotype.Component;

@Component
public class WorkCodeCategoryMappingMapper {

    WorkCodeCategoryMappingDto mapToDto(WorkCodeCategoryMapping mapping) {
        return new WorkCodeCategoryMappingDto(
                mapping.getId(),
                mapping.getSourceCategory() != null ? mapping.getSourceCategory().getId() : null,
                mapping.getTargetCategory() != null ? mapping.getTargetCategory().getId() : null,
                mapping.getMappingType(),
                mapping.getIsActive(),
                mapping.getValidFrom(),
                mapping.getValidUntil(),
                mapping.getNote(),
                mapping.getCreatedAt(),
                mapping.getUpdatedAt(),
                mapping.getArchivedAt()
        );
    }

    WorkCodeCategoryMapping toEntity(WorkCodeCategory sourceCategory,
                                    WorkCodeCategory targetCategory,
                                    WorkCodeCategoryMappingCreateRequest request) {
        return WorkCodeCategoryMapping.builder()
                .sourceCategory(sourceCategory)
                .targetCategory(targetCategory)
                .mappingType(request.mappingType().trim())
                .isActive(true)
                .validFrom(request.validFrom())
                .validUntil(request.validUntil())
                .note(request.note())
                .build();
    }
}

