package com.aleksandarparipovic.marel_app.product_family;

import com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyCreateRequest;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyDto;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyOptionDto;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyUpdateRequest;
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
 * Looking after the top level of the catalogue.
 *
 * <p><b>Blank is not a value.</b> An empty description is stored as null, not as
 * "".
 *
 * <p><b>Nothing is deleted.</b> A family a type was filed under keeps existing;
 * deactivating stops it being offered for new types and no more.
 */
@Service
@RequiredArgsConstructor
public class ProductFamilyService {

    private final ProductFamilyRepository repository;
    private final ProductFamilyMapper mapper;
    private final PasswordConfirmationService passwordConfirmation;

    @Transactional
    public ProductFamilyDto create(ProductFamilyCreateRequest request) {
        String name = request.getName().trim();
        requireNameFree(name, null);

        ProductFamily family = ProductFamily.builder()
                .name(name)
                .description(blankToNull(request.getDescription()))
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .isActive(true)
                .build();

        return mapper.toDto(repository.save(family));
    }

    @Transactional(readOnly = true)
    public Page<ProductFamilyDto> search(
            String query,
            Boolean active,
            int page,
            int size,
            Sort.Direction direction,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Specification<ProductFamily> spec = Specification.allOf();
        if (query != null && !query.isBlank()) {
            spec = spec.and(ProductFamilySpecifications.matches(query));
        }
        if (active != null) {
            spec = spec.and(ProductFamilySpecifications.isActive(active));
        }

        return repository.findAll(spec, pageable).map(mapper::toDto);
    }

    /** What a picker offers: the active families, in list order. */
    @Transactional(readOnly = true)
    public List<ProductFamilyOptionDto> options() {
        return repository.findByIsActiveTrueOrderBySortOrderAscNameAsc()
                .stream()
                .map(mapper::toOptionDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductFamilyDto get(Long id) {
        return mapper.toDto(load(id));
    }

    @Transactional
    public ProductFamilyDto update(Long id, ProductFamilyUpdateRequest request) {
        ProductFamily family = load(id);

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Naziv porodice je obavezan.");
            }
            requireNameFree(name, id);
            family.setName(name);
        }

        if (request.getDescription() != null) {
            family.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getSortOrder() != null) {
            family.setSortOrder(request.getSortOrder());
        }

        // archived_at follows on its own — the triggers set it on deactivation
        // and clear it on the way back.
        if (request.getActive() != null) {
            family.setIsActive(request.getActive());
        }

        return mapper.toDto(repository.save(family));
    }

    /** Archive, signed with the caller's re-typed password. */
    @Transactional
    public void deactivate(Long id, String password, org.springframework.security.core.Authentication authentication) {
        passwordConfirmation.confirm(authentication, password);
        ProductFamily family = load(id);
        family.setIsActive(false);
        repository.save(family);
    }

    @Transactional
    public void restore(Long id) {
        ProductFamily family = load(id);
        family.setIsActive(true);
        repository.save(family);
    }

    private ProductFamily load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Porodica proizvoda nije pronađena: " + id));
    }

    private void requireNameFree(String name, Long excludeId) {
        if (name != null && repository.nameTakenByAnother(name, excludeId)) {
            throw new ConflictException("Porodica sa tim nazivom već postoji.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
