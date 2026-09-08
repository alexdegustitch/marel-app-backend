package com.aleksandarparipovic.marel_app.product_type_attribute;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type.ProductTypeRepository;
import com.aleksandarparipovic.marel_app.product_type_attribute.dto.ProductTypeAttributeCreateRequest;
import com.aleksandarparipovic.marel_app.product_type_attribute.dto.ProductTypeAttributeDto;
import com.aleksandarparipovic.marel_app.product_type_attribute.dto.ProductTypeAttributeUpdateRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The spec schema of a type: which columns its products fill in.
 *
 * <p><b>Names are unique per type</b>, case-insensitively — two "d1"s on one type
 * would make the value form ambiguous.
 *
 * <p><b>Not deleted, hidden.</b> Deactivating an attribute takes it off the form
 * while the values already recorded against it survive. The database refuses a
 * hard delete of an attribute any product still has a value for.
 */
@Service
@RequiredArgsConstructor
public class ProductTypeAttributeService {

    private final ProductTypeAttributeRepository repository;
    private final ProductTypeRepository typeRepository;
    private final ProductTypeAttributeMapper mapper;

    /** The whole schema of a type, in column order — for the admin screen. */
    @Transactional(readOnly = true)
    public List<ProductTypeAttributeDto> listForType(Long productTypeId) {
        requireType(productTypeId);
        return repository.findByProductType_IdOrderBySortOrderAscNameAsc(productTypeId)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public ProductTypeAttributeDto create(Long productTypeId, ProductTypeAttributeCreateRequest request) {
        ProductType type = requireType(productTypeId);
        String name = request.getName().trim();
        requireNameFree(productTypeId, name, null);

        ProductTypeAttribute attribute = ProductTypeAttribute.builder()
                .productType(type)
                .name(name)
                .unit(blankToNull(request.getUnit()))
                .dataType(request.getDataType() != null ? request.getDataType() : AttributeDataType.TEXT)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .required(request.getRequired() != null ? request.getRequired() : false)
                .isActive(true)
                .build();

        return mapper.toDto(repository.save(attribute));
    }

    @Transactional
    public ProductTypeAttributeDto update(Long id, ProductTypeAttributeUpdateRequest request) {
        ProductTypeAttribute attribute = load(id);

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Naziv atributa je obavezan.");
            }
            requireNameFree(attribute.getProductType().getId(), name, id);
            attribute.setName(name);
        }
        if (request.getUnit() != null) {
            attribute.setUnit(blankToNull(request.getUnit()));
        }
        if (request.getDataType() != null) {
            attribute.setDataType(request.getDataType());
        }
        if (request.getSortOrder() != null) {
            attribute.setSortOrder(request.getSortOrder());
        }
        if (request.getRequired() != null) {
            attribute.setRequired(request.getRequired());
        }
        if (request.getActive() != null) {
            attribute.setIsActive(request.getActive());
        }

        return mapper.toDto(repository.save(attribute));
    }

    /** Deactivate. The recorded values keep existing; the column leaves the form. */
    @Transactional
    public void deactivate(Long id) {
        ProductTypeAttribute attribute = load(id);
        attribute.setIsActive(false);
        repository.save(attribute);
    }

    @Transactional
    public void restore(Long id) {
        ProductTypeAttribute attribute = load(id);
        attribute.setIsActive(true);
        repository.save(attribute);
    }

    private ProductTypeAttribute load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Atribut nije pronađen: " + id));
    }

    private ProductType requireType(Long productTypeId) {
        return typeRepository.findById(productTypeId)
                .orElseThrow(() -> new EntityNotFoundException("Tip proizvoda nije pronađen: " + productTypeId));
    }

    private void requireNameFree(Long productTypeId, String name, Long excludeId) {
        if (name != null && repository.nameTakenInTypeByAnother(productTypeId, name, excludeId)) {
            throw new ConflictException("Atribut sa tim nazivom već postoji na ovom tipu.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
