package com.aleksandarparipovic.marel_app.variant_attribute;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type.ProductTypeRepository;
import com.aleksandarparipovic.marel_app.variant_attribute.dto.VariantAttributeDtos.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The šifarnik of variant attributes and their options, per product type.
 *
 * <p><b>Archiving, never deleting.</b> A value that a variant or an operation
 * binding references must keep meaning what it meant; archiving only takes it
 * off the forms. The rule the whole design leans on (SPEC odluka 15): an
 * archived option is not offered for NEW variants or bindings, the existing
 * ones stay valid.
 */
@Service
@RequiredArgsConstructor
public class VariantAttributeService {

    private final VariantAttributeRepository attributes;
    private final VariantAttributeOptionRepository options;
    private final ProductTypeRepository types;

    // ── attributes ───────────────────────────────────────────────────────────

    /** The whole schema of a type, options nested — one call feeds the admin drawer. */
    @Transactional(readOnly = true)
    public List<VariantAttributeDto> listForType(Long productTypeId) {
        requireType(productTypeId);
        List<VariantAttribute> list = attributes.findByProductType_IdOrderBySortOrderAscNameAsc(productTypeId);
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<VariantAttributeOption>> byAttribute = options
                .findByAttribute_IdInOrderBySortOrderAscValueAsc(list.stream().map(VariantAttribute::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(o -> o.getAttribute().getId()));
        return list.stream()
                .map(a -> toDto(a, byAttribute.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    @Transactional
    public VariantAttributeDto create(Long productTypeId, VariantAttributeCreateRequest request) {
        ProductType type = requireType(productTypeId);
        String name = request.getName().trim();
        if (attributes.nameTakenInTypeByAnother(productTypeId, name, null)) {
            throw new ConflictException("Varijantni atribut sa tim nazivom već postoji na ovom tipu.");
        }
        VariantAttribute attribute = VariantAttribute.builder()
                .productType(type)
                .name(name)
                .required(request.getRequired() == null || request.getRequired())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();
        return toDto(attributes.save(attribute), List.of());
    }

    @Transactional
    public VariantAttributeDto update(Long id, VariantAttributeUpdateRequest request) {
        VariantAttribute attribute = loadAttribute(id);
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Naziv varijantnog atributa je obavezan.");
            }
            if (attributes.nameTakenInTypeByAnother(attribute.getProductType().getId(), name, id)) {
                throw new ConflictException("Varijantni atribut sa tim nazivom već postoji na ovom tipu.");
            }
            attribute.setName(name);
        }
        if (request.getRequired() != null) {
            attribute.setRequired(request.getRequired());
        }
        if (request.getSortOrder() != null) {
            attribute.setSortOrder(request.getSortOrder());
        }
        VariantAttribute saved = attributes.save(attribute);
        return toDto(saved, options.findByAttribute_IdOrderBySortOrderAscValueAsc(id));
    }

    @Transactional
    public void archive(Long id) {
        VariantAttribute attribute = loadAttribute(id);
        attribute.archive();
        attributes.save(attribute);
    }

    @Transactional
    public void restore(Long id) {
        VariantAttribute attribute = loadAttribute(id);
        attribute.restore();
        attributes.save(attribute);
    }

    // ── options ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VariantAttributeOptionDto> listOptions(Long attributeId) {
        loadAttribute(attributeId);
        return options.findByAttribute_IdOrderBySortOrderAscValueAsc(attributeId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public VariantAttributeOptionDto createOption(Long attributeId, VariantAttributeOptionCreateRequest request) {
        VariantAttribute attribute = loadAttribute(attributeId);
        if (attribute.isArchived()) {
            throw new ConflictException("Arhiviranom varijantnom atributu se ne dodaju opcije.");
        }
        String value = request.getValue().trim();
        if (options.valueTakenInAttributeByAnother(attributeId, value, null)) {
            throw new ConflictException("Opcija sa tom vrednošću već postoji na ovom atributu.");
        }
        VariantAttributeOption option = VariantAttributeOption.builder()
                .attribute(attribute)
                .value(value)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();
        return toDto(options.save(option));
    }

    @Transactional
    public VariantAttributeOptionDto updateOption(Long id, VariantAttributeOptionUpdateRequest request) {
        VariantAttributeOption option = loadOption(id);
        if (request.getValue() != null) {
            String value = request.getValue().trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Vrednost opcije je obavezna.");
            }
            if (options.valueTakenInAttributeByAnother(option.getAttribute().getId(), value, id)) {
                throw new ConflictException("Opcija sa tom vrednošću već postoji na ovom atributu.");
            }
            option.setValue(value);
        }
        if (request.getSortOrder() != null) {
            option.setSortOrder(request.getSortOrder());
        }
        return toDto(options.save(option));
    }

    /** Off the forms; every variant and binding that picked it stays valid. */
    @Transactional
    public void archiveOption(Long id) {
        VariantAttributeOption option = loadOption(id);
        option.archive();
        options.save(option);
    }

    @Transactional
    public void restoreOption(Long id) {
        VariantAttributeOption option = loadOption(id);
        option.restore();
        options.save(option);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private VariantAttribute loadAttribute(Long id) {
        return attributes.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Varijantni atribut nije pronađen: " + id));
    }

    private VariantAttributeOption loadOption(Long id) {
        return options.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Opcija varijantnog atributa nije pronađena: " + id));
    }

    private ProductType requireType(Long productTypeId) {
        return types.findById(productTypeId)
                .orElseThrow(() -> new EntityNotFoundException("Tip proizvoda nije pronađen: " + productTypeId));
    }

    private VariantAttributeDto toDto(VariantAttribute attribute, List<VariantAttributeOption> attributeOptions) {
        return VariantAttributeDto.builder()
                .id(attribute.getId())
                .productTypeId(attribute.getProductType().getId())
                .name(attribute.getName())
                .required(attribute.getRequired())
                .sortOrder(attribute.getSortOrder())
                .active(attribute.getIsActive())
                .archived(attribute.isArchived())
                .options(attributeOptions.stream().map(this::toDto).toList())
                .build();
    }

    private VariantAttributeOptionDto toDto(VariantAttributeOption option) {
        return VariantAttributeOptionDto.builder()
                .id(option.getId())
                .variantAttributeId(option.getAttribute().getId())
                .value(option.getValue())
                .sortOrder(option.getSortOrder())
                .active(option.getIsActive())
                .archived(option.isArchived())
                .build();
    }
}
