package com.aleksandarparipovic.marel_app.product_type;

import com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product_family.ProductFamily;
import com.aleksandarparipovic.marel_app.product_family.ProductFamilyRepository;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeCreateRequest;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeDto;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeOptionDto;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeUpdateRequest;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Looking after the middle level of the catalogue.
 *
 * <p><b>The code is unique per family, not globally.</b> Every uniqueness check
 * is scoped to the family the type belongs to — and re-run against the new
 * family when a type is moved.
 *
 * <p><b>Nothing is deleted.</b> Products point at the type they are;
 * deactivating stops the type being offered for new products and no more.
 */
@Service
@RequiredArgsConstructor
public class ProductTypeService {

    private final ProductTypeRepository repository;
    private final ProductFamilyRepository familyRepository;
    private final ProductTypeMapper mapper;
    private final PasswordConfirmationService passwordConfirmation;

    @Transactional
    public ProductTypeDto create(ProductTypeCreateRequest request) {
        ProductFamily family = loadFamily(request.getFamilyId());
        String name = request.getName().trim();
        String code = request.getCode().trim();

        requireCodeFree(family.getId(), code, null);

        ProductType type = ProductType.builder()
                .family(family)
                .name(name)
                .code(code)
                .description(blankToNull(request.getDescription()))
                .note(blankToNull(request.getNote()))
                .standard(blankToNull(request.getStandard()))
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .isActive(true)
                .build();

        return mapper.toDto(repository.save(type));
    }

    @Transactional(readOnly = true)
    public Page<ProductTypeDto> search(
            String query,
            Long familyId,
            Boolean active,
            int page,
            int size,
            Sort.Direction direction,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Specification<ProductType> spec = Specification.allOf();
        if (query != null && !query.isBlank()) {
            spec = spec.and(ProductTypeSpecifications.matches(query));
        }
        if (familyId != null) {
            spec = spec.and(ProductTypeSpecifications.inFamily(familyId));
        }
        if (active != null) {
            spec = spec.and(ProductTypeSpecifications.isActive(active));
        }

        return repository.findAll(spec, pageable).map(mapper::toDto);
    }

    /**
     * What a picker offers: the active types, in list order. Narrowed to one
     * family when {@code familyId} is given — which is how the product form
     * offers only the types under the family already chosen.
     */
    @Transactional(readOnly = true)
    public List<ProductTypeOptionDto> options(Long familyId) {
        List<ProductType> types = (familyId != null)
                ? repository.findByFamily_IdAndIsActiveTrueOrderBySortOrderAscNameAsc(familyId)
                : repository.findByIsActiveTrueOrderBySortOrderAscNameAsc();
        return types.stream().map(mapper::toOptionDto).toList();
    }

    @Transactional(readOnly = true)
    public ProductTypeDto get(Long id) {
        return mapper.toDto(load(id));
    }

    @Transactional
    public ProductTypeDto update(Long id, ProductTypeUpdateRequest request) {
        ProductType type = load(id);

        // Resolve the family first: a code change and a family change both bear on
        // the uniqueness check, so the final family and code must be known together.
        ProductFamily targetFamily = type.getFamily();
        if (request.getFamilyId() != null
                && !request.getFamilyId().equals(type.getFamily().getId())) {
            targetFamily = loadFamily(request.getFamilyId());
        }

        String targetCode = type.getCode();
        if (request.getCode() != null) {
            String code = request.getCode().trim();
            if (code.isEmpty()) {
                throw new IllegalArgumentException("Kod tipa je obavezan.");
            }
            targetCode = code;
        }

        if (!targetFamily.getId().equals(type.getFamily().getId())
                || !targetCode.equalsIgnoreCase(type.getCode())) {
            requireCodeFree(targetFamily.getId(), targetCode, id);
        }

        type.setFamily(targetFamily);
        type.setCode(targetCode);

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Naziv tipa je obavezan.");
            }
            type.setName(name);
        }

        if (request.getDescription() != null) {
            type.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getNote() != null) {
            type.setNote(blankToNull(request.getNote()));
        }
        if (request.getStandard() != null) {
            type.setStandard(blankToNull(request.getStandard()));
        }
        if (request.getSortOrder() != null) {
            type.setSortOrder(request.getSortOrder());
        }
        if (request.getActive() != null) {
            type.setIsActive(request.getActive());
        }

        return mapper.toDto(repository.save(type));
    }

    /** Archive, signed with the caller's re-typed password. */
    @Transactional
    public void deactivate(Long id, String password, org.springframework.security.core.Authentication authentication) {
        passwordConfirmation.confirm(authentication, password);
        ProductType type = load(id);
        type.setIsActive(false);
        repository.save(type);
    }

    @Transactional
    public void restore(Long id) {
        ProductType type = load(id);
        type.setIsActive(true);
        repository.save(type);
    }

    private ProductType load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Tip proizvoda nije pronađen: " + id));
    }

    private ProductFamily loadFamily(Long familyId) {
        return familyRepository.findById(familyId)
                .orElseThrow(() -> new EntityNotFoundException("Porodica proizvoda nije pronađena: " + familyId));
    }

    private void requireCodeFree(Long familyId, String code, Long excludeId) {
        if (code != null && repository.codeTakenInFamilyByAnother(familyId, code, excludeId)) {
            throw new ConflictException("Tip sa tim kodom već postoji u ovoj porodici.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
