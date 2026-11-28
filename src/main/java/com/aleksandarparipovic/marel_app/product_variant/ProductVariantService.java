package com.aleksandarparipovic.marel_app.product_variant;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_variant.dto.ProductVariantDtos.*;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttribute;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeOption;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeOptionRepository;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The variants of a product: which combinations of option values it exists in.
 *
 * <p>The rules the database cannot hold are held here, each with a test:
 * every option's attribute belongs to the TYPE of this product; one option per
 * attribute; every REQUIRED attribute of the type is covered (optional ones may
 * be left out); the canonical {@code signature} is written in the same
 * transaction as the values; and a variant that an order line or a
 * manufacturing time references is FROZEN — its values are refused, a changed
 * combination is a new variant (SPEC odluka 13). The label stays free.
 */
@Service
@RequiredArgsConstructor
public class ProductVariantService {

    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final VariantAttributeRepository attributes;
    private final VariantAttributeOptionRepository options;

    @Transactional(readOnly = true)
    public List<ProductVariantDto> listForProduct(Long productId, boolean onlyActive) {
        requireProduct(productId);
        List<ProductVariant> list = onlyActive
                ? variants.findByProduct_IdAndArchivedAtIsNullOrderByIdAsc(productId)
                : variants.findByProduct_IdOrderByIdAsc(productId);
        return list.stream().map(v -> toDto(v, variants.isReferenced(v.getId()))).toList();
    }

    @Transactional
    public ProductVariantDto create(Long productId, ProductVariantCreateRequest request) {
        Product product = requireProduct(productId);
        List<VariantAttributeOption> picked = resolveAndValidateOptions(product, request.getOptionIds());

        String signature = signatureOf(picked);
        if (variants.existsByProduct_IdAndSignatureAndArchivedAtIsNull(productId, signature)) {
            throw new ConflictException("Varijanta sa tom kombinacijom vrednosti već postoji na ovom proizvodu.");
        }

        ProductVariant variant = ProductVariant.builder()
                .product(product)
                .label(blankToNull(request.getLabel()))
                .signature(signature)
                .build();
        for (VariantAttributeOption option : picked) {
            variant.getValues().add(ProductVariantValue.builder()
                    .variant(variant)
                    .attribute(option.getAttribute())
                    .option(option)
                    .build());
        }
        ProductVariant saved = variants.save(variant);
        return toDto(saved, false);
    }

    @Transactional
    public ProductVariantDto update(Long id, ProductVariantUpdateRequest request) {
        ProductVariant variant = load(id);
        boolean referenced = variants.isReferenced(id);

        if (request.getLabel() != null) {
            variant.setLabel(blankToNull(request.getLabel()));
        }

        if (request.getOptionIds() != null) {
            if (referenced) {
                throw new ConflictException(
                        "Varijanta je već korišćena (nalog ili vreme izrade) i njene vrednosti su zamrznute. "
                                + "Druga kombinacija je NOVA varijanta.");
            }
            List<VariantAttributeOption> picked =
                    resolveAndValidateOptions(variant.getProduct(), request.getOptionIds());
            String signature = signatureOf(picked);
            if (!signature.equals(variant.getSignature())
                    && variants.existsByProduct_IdAndSignatureAndArchivedAtIsNull(
                            variant.getProduct().getId(), signature)) {
                throw new ConflictException("Varijanta sa tom kombinacijom vrednosti već postoji na ovom proizvodu.");
            }
            variant.getValues().clear();
            for (VariantAttributeOption option : picked) {
                variant.getValues().add(ProductVariantValue.builder()
                        .variant(variant)
                        .attribute(option.getAttribute())
                        .option(option)
                        .build());
            }
            // Same transaction as the values — the signature is never stale.
            variant.setSignature(signature);
        }

        return toDto(variants.save(variant), referenced);
    }

    /** Off the pickers; order lines that hold it keep holding it. */
    @Transactional
    public void archive(Long id) {
        ProductVariant variant = load(id);
        variant.archive();
        variants.save(variant);
    }

    @Transactional
    public void restore(Long id) {
        ProductVariant variant = load(id);
        if (variants.existsByProduct_IdAndSignatureAndArchivedAtIsNull(
                variant.getProduct().getId(), variant.getSignature())) {
            throw new ConflictException("Živa varijanta sa tom kombinacijom već postoji — ova ostaje arhivirana.");
        }
        variant.restore();
        variants.save(variant);
    }

    /**
     * The live variants of the attribute's type that carry no value for it —
     * read by the šifarnik BEFORE flipping the attribute to "obavezan", so the
     * flip is an informed decision. The flip itself stays allowed: existing
     * variants were created legally and remain valid, the rule binds only NEW
     * combinations.
     */
    @Transactional(readOnly = true)
    public List<VariantMissingValueRow> listMissingValueForAttribute(Long attributeId) {
        VariantAttribute attribute = attributes.findById(attributeId)
                .orElseThrow(() -> new EntityNotFoundException("Varijantni atribut nije pronađen: " + attributeId));
        return variants.findLiveMissingAttributeValue(attribute.getProductType().getId(), attributeId)
                .stream()
                .map(v -> {
                    String joined = v.getValues().stream()
                            .map(value -> value.getOption().getValue())
                            .collect(Collectors.joining(" / "));
                    return VariantMissingValueRow.builder()
                            .variantId(v.getId())
                            .productId(v.getProduct().getId())
                            .productName(v.getProduct().getProductName())
                            .variantLabel(v.getLabel() != null && !v.getLabel().isBlank() ? v.getLabel() : joined)
                            .inUse(variants.isReferenced(v.getId()))
                            .build();
                })
                .toList();
    }

    // ── invariants ───────────────────────────────────────────────────────────

    /**
     * Loads the picked options and holds the three service-side invariants:
     * live options of live attributes of THIS product's type, one per
     * attribute, every required attribute covered.
     */
    private List<VariantAttributeOption> resolveAndValidateOptions(Product product, List<Long> optionIds) {
        if (product.getProductType() == null) {
            throw new ConflictException("Proizvod nema tip, pa ne može imati varijante.");
        }
        Long typeId = product.getProductType().getId();

        List<Long> distinctIds = optionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            throw new IllegalArgumentException("Varijanta mora imati bar jednu vrednost atributa.");
        }
        List<VariantAttributeOption> picked = options.findAllById(distinctIds);
        if (picked.size() != distinctIds.size()) {
            throw new EntityNotFoundException("Neka od izabranih opcija ne postoji.");
        }

        Set<Long> seenAttributes = new HashSet<>();
        for (VariantAttributeOption option : picked) {
            VariantAttribute attribute = option.getAttribute();
            if (!Objects.equals(attribute.getProductType().getId(), typeId)) {
                throw new ConflictException(
                        "Opcija „" + option.getValue() + "“ ne pripada tipu ovog proizvoda.");
            }
            if (option.isArchived() || attribute.isArchived()) {
                throw new ConflictException(
                        "Opcija „" + option.getValue() + "“ je arhivirana i ne ulazi u nove varijante.");
            }
            if (!seenAttributes.add(attribute.getId())) {
                throw new ConflictException(
                        "Atribut „" + attribute.getName() + "“ je izabran dvaput — varijanta nosi jednu vrednost po atributu.");
            }
        }

        List<VariantAttribute> required = attributes
                .findByProductType_IdAndArchivedAtIsNullOrderBySortOrderAscNameAsc(typeId)
                .stream().filter(a -> Boolean.TRUE.equals(a.getRequired())).toList();
        for (VariantAttribute attribute : required) {
            if (!seenAttributes.contains(attribute.getId())) {
                throw new ConflictException(
                        "Obavezni atribut „" + attribute.getName() + "“ nema izabranu vrednost.");
            }
        }
        return picked;
    }

    /** Canonical form: option ids sorted ascending, joined with "|". */
    static String signatureOf(List<VariantAttributeOption> picked) {
        return picked.stream()
                .map(VariantAttributeOption::getId)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining("|"));
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private ProductVariant load(Long id) {
        return variants.findWithValuesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Varijanta nije pronađena: " + id));
    }

    private Product requireProduct(Long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Proizvod nije pronađen: " + productId));
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ProductVariantDto toDto(ProductVariant variant, boolean inUse) {
        List<ProductVariantValue> ordered = variant.getValues().stream()
                .sorted(Comparator
                        .comparing((ProductVariantValue v) -> v.getAttribute().getSortOrder())
                        .thenComparing(v -> v.getAttribute().getName()))
                .toList();
        return ProductVariantDto.builder()
                .id(variant.getId())
                .productId(variant.getProduct().getId())
                .label(variant.getLabel())
                .valuesLabel(ordered.stream()
                        .map(v -> v.getOption().getValue())
                        .collect(Collectors.joining(" / ")))
                .values(ordered.stream()
                        .map(v -> ProductVariantValueDto.builder()
                                .attributeId(v.getAttribute().getId())
                                .attributeName(v.getAttribute().getName())
                                .optionId(v.getOption().getId())
                                .optionValue(v.getOption().getValue())
                                .build())
                        .toList())
                .active(variant.getIsActive())
                .archived(variant.isArchived())
                .inUse(inUse)
                .build();
    }
}
