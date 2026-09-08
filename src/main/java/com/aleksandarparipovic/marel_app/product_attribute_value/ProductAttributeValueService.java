package com.aleksandarparipovic.marel_app.product_attribute_value;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_attribute_value.dto.ProductAttributeValueDto;
import com.aleksandarparipovic.marel_app.product_attribute_value.dto.ProductAttributeValuesSaveRequest;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type_attribute.AttributeDataType;
import com.aleksandarparipovic.marel_app.product_type_attribute.ProductTypeAttribute;
import com.aleksandarparipovic.marel_app.product_type_attribute.ProductTypeAttributeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A product's spec values.
 *
 * <p><b>The invariant this service exists to keep:</b> a value may only be
 * recorded against an attribute that belongs to the product's own type. The
 * database cannot state it (a product's type is nullable, so no composite FK
 * reaches it), so every write passes through here.
 *
 * <p>Saving is a REPLACE: the request is the product's complete set of values.
 * An attribute left out, or sent blank, is cleared. A product with no type can
 * hold no values at all — there are no attributes for it to fill.
 */
@Service
@RequiredArgsConstructor
public class ProductAttributeValueService {

    private final ProductAttributeValueRepository valueRepository;
    private final ProductTypeAttributeRepository attributeRepository;
    private final ProductRepository productRepository;

    /**
     * The product's spec form: every active attribute of its type, with the
     * product's current value (or null). Empty when the product has no type.
     */
    @Transactional(readOnly = true)
    public List<ProductAttributeValueDto> getForProduct(Long productId) {
        Product product = loadProduct(productId);
        ProductType type = product.getProductType();
        if (type == null) {
            return List.of();
        }

        Map<Long, String> valueByAttributeId = new HashMap<>();
        for (ProductAttributeValue v : valueRepository.findByProduct_Id(productId)) {
            valueByAttributeId.put(v.getAttribute().getId(), v.getValue());
        }

        return attributeRepository
                .findByProductType_IdAndIsActiveTrueOrderBySortOrderAscNameAsc(type.getId())
                .stream()
                .map(attr -> ProductAttributeValueDto.builder()
                        .attributeId(attr.getId())
                        .name(attr.getName())
                        .unit(attr.getUnit())
                        .dataType(attr.getDataType())
                        .sortOrder(attr.getSortOrder())
                        .required(attr.getRequired())
                        .value(valueByAttributeId.get(attr.getId()))
                        .build())
                .toList();
    }

    /**
     * Replace a product's spec values with the ones in the request.
     *
     * @throws ConflictException     if the product has no type, if an attribute
     *                               does not belong to that type or is inactive,
     *                               if the same attribute appears twice, or if a
     *                               required attribute is left without a value
     * @throws IllegalArgumentException if a NUMBER attribute gets a non-numeric value
     */
    @Transactional
    public List<ProductAttributeValueDto> saveForProduct(Long productId, ProductAttributeValuesSaveRequest request) {
        Product product = loadProduct(productId);
        ProductType type = product.getProductType();
        if (type == null) {
            throw new ConflictException("Proizvod nema tip, pa ne može imati vrednosti atributa. Dodelite tip pa pokušajte ponovo.");
        }

        // Desired final state: attributeId -> normalised value (null means "cleared").
        // LinkedHashMap keeps the request's order for a stable, readable outcome.
        Map<Long, String> desired = new LinkedHashMap<>();
        Map<Long, ProductTypeAttribute> attributesById = new HashMap<>();

        for (ProductAttributeValuesSaveRequest.Item item : request.getValues()) {
            Long attributeId = item.getAttributeId();
            if (desired.containsKey(attributeId)) {
                throw new ConflictException("Isti atribut je naveden više puta.");
            }

            ProductTypeAttribute attribute = attributeRepository.findById(attributeId)
                    .orElseThrow(() -> new EntityNotFoundException("Atribut nije pronađen: " + attributeId));

            // ── THE INVARIANT ──────────────────────────────────────────────
            if (!attribute.getProductType().getId().equals(type.getId())) {
                throw new ConflictException(
                        "Atribut ne pripada tipu ovog proizvoda.");
            }
            if (Boolean.FALSE.equals(attribute.getIsActive())) {
                throw new ConflictException("Atribut nije aktivan: " + attribute.getName());
            }

            String value = blankToNull(item.getValue());
            if (value != null && attribute.getDataType() == AttributeDataType.NUMBER && !isNumeric(value)) {
                throw new IllegalArgumentException(
                        "Vrednost atributa \"" + attribute.getName() + "\" mora biti broj.");
            }

            attributesById.put(attributeId, attribute);
            desired.put(attributeId, value);
        }

        // Required attributes must end up with a value.
        for (ProductTypeAttribute attr :
                attributeRepository.findByProductType_IdAndIsActiveTrueOrderBySortOrderAscNameAsc(type.getId())) {
            if (Boolean.TRUE.equals(attr.getRequired()) && blankToNull(desired.get(attr.getId())) == null) {
                throw new ConflictException("Obavezan atribut nema vrednost: " + attr.getName());
            }
        }

        applyReplace(product, desired, attributesById);
        return getForProduct(productId);
    }

    /** Upsert the non-null desired values; delete every existing value now cleared or absent. */
    private void applyReplace(
            Product product,
            Map<Long, String> desired,
            Map<Long, ProductTypeAttribute> attributesById) {

        Map<Long, ProductAttributeValue> existingByAttributeId = new HashMap<>();
        for (ProductAttributeValue v : valueRepository.findByProduct_Id(product.getId())) {
            existingByAttributeId.put(v.getAttribute().getId(), v);
        }

        List<ProductAttributeValue> toSave = new ArrayList<>();
        List<ProductAttributeValue> toDelete = new ArrayList<>();

        for (Map.Entry<Long, String> entry : desired.entrySet()) {
            Long attributeId = entry.getKey();
            String value = entry.getValue();
            ProductAttributeValue existing = existingByAttributeId.remove(attributeId);

            if (value == null) {
                if (existing != null) {
                    toDelete.add(existing);
                }
                continue;
            }
            if (existing != null) {
                existing.setValue(value);
                toSave.add(existing);
            } else {
                toSave.add(ProductAttributeValue.builder()
                        .product(product)
                        .attribute(attributesById.get(attributeId))
                        .value(value)
                        .build());
            }
        }

        // Anything the product had but the request did not mention is cleared.
        toDelete.addAll(existingByAttributeId.values());

        if (!toDelete.isEmpty()) {
            valueRepository.deleteAll(toDelete);
        }
        if (!toSave.isEmpty()) {
            valueRepository.saveAll(toSave);
        }
    }

    private Product loadProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Proizvod nije pronađen: " + productId));
    }

    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value.trim().replace(',', '.'));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
