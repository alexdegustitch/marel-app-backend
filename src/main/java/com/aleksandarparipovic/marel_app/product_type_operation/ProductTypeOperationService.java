package com.aleksandarparipovic.marel_app.product_type_operation;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type.ProductTypeRepository;
import com.aleksandarparipovic.marel_app.product_type_operation.dto.ProductTypeOperationCreateRequest;
import com.aleksandarparipovic.marel_app.product_type_operation.dto.ProductTypeOperationDto;
import com.aleksandarparipovic.marel_app.product_type_operation.dto.ProductTypeOperationUpdateRequest;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The operation TEMPLATE of a product type: a blueprint a new product may copy.
 *
 * <p><b>Names are unique per type</b>, case-insensitively — the same rule the
 * product level enforces, so a copy cannot collide with itself.
 *
 * <p><b>Deactivated, not deleted.</b> Retiring a template operation takes it off
 * the copy picker while leaving the product operations that were once copied from
 * it entirely untouched (there is no link back — the copy is a snapshot).
 */
@Service
@RequiredArgsConstructor
public class ProductTypeOperationService {

    private final ProductTypeOperationRepository repository;
    private final ProductTypeRepository typeRepository;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final ProductTypeOperationMapper mapper;

    /** The whole template of a type, in order — for the admin screen. */
    @Transactional(readOnly = true)
    public List<ProductTypeOperationDto> listForType(Long productTypeId) {
        requireType(productTypeId);
        return repository.findByProductType_IdOrderBySortOrderAscOpNameAsc(productTypeId)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public ProductTypeOperationDto create(Long productTypeId, ProductTypeOperationCreateRequest request) {
        ProductType type = requireType(productTypeId);
        String opName = request.getOpName().trim();
        requireNameFree(productTypeId, opName, null);

        ProductTypeOperation operation = ProductTypeOperation.builder()
                .productType(type)
                .opName(opName)
                .description(blankToNull(request.getDescription()))
                .defaultMinNorm(request.getDefaultMinNorm())
                .defaultMaxNorm(request.getDefaultMaxNorm())
                .defaultUnitsPerProduct(request.getDefaultUnitsPerProduct())
                .workCodeCategory(resolveWorkCodeCategory(request.getWorkCodeCategoryId()))
                .normRequired(request.getNormRequired() == null || request.getNormRequired())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .isActive(true)
                .build();

        return mapper.toDto(repository.save(operation));
    }

    @Transactional
    public ProductTypeOperationDto update(Long id, ProductTypeOperationUpdateRequest request) {
        ProductTypeOperation operation = load(id);

        if (request.getOpName() != null) {
            String opName = request.getOpName().trim();
            if (opName.isEmpty()) {
                throw new IllegalArgumentException("Naziv operacije je obavezan.");
            }
            requireNameFree(operation.getProductType().getId(), opName, id);
            operation.setOpName(opName);
        }
        if (request.getDescription() != null) {
            operation.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getDefaultMinNorm() != null) {
            operation.setDefaultMinNorm(request.getDefaultMinNorm());
        }
        if (request.getDefaultMaxNorm() != null) {
            operation.setDefaultMaxNorm(request.getDefaultMaxNorm());
        }
        if (request.getDefaultUnitsPerProduct() != null) {
            operation.setDefaultUnitsPerProduct(request.getDefaultUnitsPerProduct());
        }
        if (request.getWorkCodeCategoryId() != null) {
            operation.setWorkCodeCategory(resolveWorkCodeCategory(request.getWorkCodeCategoryId()));
        }
        if (request.getNormRequired() != null) {
            operation.setNormRequired(request.getNormRequired());
        }
        if (request.getSortOrder() != null) {
            operation.setSortOrder(request.getSortOrder());
        }
        if (request.getActive() != null) {
            operation.setIsActive(request.getActive());
        }

        return mapper.toDto(repository.save(operation));
    }

    /** Deactivate. The template leaves the copy picker; products already copied are untouched. */
    @Transactional
    public void deactivate(Long id) {
        ProductTypeOperation operation = load(id);
        operation.setIsActive(false);
        repository.save(operation);
    }

    @Transactional
    public void restore(Long id) {
        ProductTypeOperation operation = load(id);
        operation.setIsActive(true);
        repository.save(operation);
    }

    private ProductTypeOperation load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Operacija tipa nije pronađena: " + id));
    }

    private ProductType requireType(Long productTypeId) {
        return typeRepository.findById(productTypeId)
                .orElseThrow(() -> new EntityNotFoundException("Tip proizvoda nije pronađen: " + productTypeId));
    }

    private WorkCodeCategory resolveWorkCodeCategory(Long id) {
        if (id == null) {
            return null;
        }
        return workCodeCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Kategorija radnog koda nije pronađena: " + id));
    }

    private void requireNameFree(Long productTypeId, String opName, Long excludeId) {
        if (opName != null && repository.opNameTakenInTypeByAnother(productTypeId, opName, excludeId)) {
            throw new ConflictException("Operacija sa tim nazivom već postoji na ovom tipu.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
