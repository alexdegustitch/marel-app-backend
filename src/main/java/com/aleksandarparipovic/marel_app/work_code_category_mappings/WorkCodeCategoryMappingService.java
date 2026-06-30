package com.aleksandarparipovic.marel_app.work_code_category_mappings;

import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingCreateRequest;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingDto;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.dto.WorkCodeCategoryMappingUpdateRequest;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkCodeCategoryMappingService {

    private final WorkCodeCategoryMappingRepository repository;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final WorkCodeCategoryMappingMapper mapper;

    @Transactional(readOnly = true)
    public List<WorkCodeCategoryMappingDto> getAllActiveMappings() {
        return repository.findByIsActiveTrueAndArchivedAtIsNullOrderByIdAsc()
                .stream()
                .map(mapper::mapToDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkCodeCategoryMappingDto getById(Long id) {
        WorkCodeCategoryMapping mapping = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new EntityNotFoundException("Work code category mapping not found: " + id));
        return mapper.mapToDto(mapping);
    }

    @Transactional
    public WorkCodeCategoryMappingDto create(WorkCodeCategoryMappingCreateRequest request) {
        validateRequest(request.sourceCategoryId(), request.targetCategoryId(), request.validFrom(), request.validUntil());

        WorkCodeCategory sourceCategory = resolveCategory(request.sourceCategoryId());
        WorkCodeCategory targetCategory = resolveCategory(request.targetCategoryId());

        WorkCodeCategoryMapping mapping = mapper.toEntity(sourceCategory, targetCategory, request);
        WorkCodeCategoryMapping saved = repository.save(mapping);
        return mapper.mapToDto(saved);
    }

    @Transactional
    public WorkCodeCategoryMappingDto update(Long id, WorkCodeCategoryMappingUpdateRequest request) {
        validateRequest(request.sourceCategoryId(), request.targetCategoryId(), request.validFrom(), request.validUntil());

        WorkCodeCategoryMapping mapping = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new EntityNotFoundException("Work code category mapping not found: " + id));

        WorkCodeCategory sourceCategory = resolveCategory(request.sourceCategoryId());
        WorkCodeCategory targetCategory = resolveCategory(request.targetCategoryId());

        mapping.setSourceCategory(sourceCategory);
        mapping.setTargetCategory(targetCategory);
        mapping.setMappingType(request.mappingType().trim());
        mapping.setValidFrom(request.validFrom());
        mapping.setValidUntil(request.validUntil());
        mapping.setNote(request.note());
        mapping.setIsActive(request.isActive());

        return mapper.mapToDto(repository.save(mapping));
    }

    @Transactional
    public WorkCodeCategoryMappingDto archive(Long id) {
        WorkCodeCategoryMapping mapping = repository.findByIdAndArchivedAtIsNull(id)
                .orElseThrow(() -> new EntityNotFoundException("Work code category mapping not found: " + id));
        mapping.setIsActive(false);
        return mapper.mapToDto(repository.save(mapping));
    }

    private WorkCodeCategory resolveCategory(Long id) {
        return workCodeCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Work code category not found: " + id));
    }

    private void validateRequest(Long sourceCategoryId, Long targetCategoryId, java.time.LocalDate validFrom, java.time.LocalDate validUntil) {
        if (sourceCategoryId == null || targetCategoryId == null) {
            throw new IllegalArgumentException("Source and target category ids are required");
        }
        if (sourceCategoryId.equals(targetCategoryId)) {
            throw new IllegalArgumentException("Source and target category must be different");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("validFrom is required");
        }
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil must be greater than or equal to validFrom");
        }
    }
}

