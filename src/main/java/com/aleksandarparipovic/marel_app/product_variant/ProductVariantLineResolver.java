package com.aleksandarparipovic.marel_app.product_variant;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product.Product;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one rule every consumer of a variant shares (SPEC odluka 10): a product
 * that HAS live variants is ordered AS a variant — the line must name one; a
 * product without them must not. Used by production orders, sample orders and
 * manufacturing times, so the sentence is written once.
 *
 * <p>Manufacturing times relax the first half (SPEC odluka 9): a time without
 * a variant on a variant-ful product means "all variants", so they call
 * {@link #resolveOptional} instead.
 */
@Component
@RequiredArgsConstructor
public class ProductVariantLineResolver {

    private final ProductVariantRepository variants;

    /** For order lines: required when the product has live variants. */
    public ProductVariant resolveForLine(Product product, Long productVariantId) {
        if (productVariantId == null) {
            if (variants.existsByProduct_IdAndArchivedAtIsNull(product.getId())) {
                throw new ConflictException("Proizvod „" + product.getProductName()
                        + "“ ima varijante — stavka mora da izabere varijantu.");
            }
            return null;
        }
        return requireOwnLiveVariant(product, productVariantId);
    }

    /** For manufacturing times: null is legal and means "all variants". */
    public ProductVariant resolveOptional(Product product, Long productVariantId) {
        if (productVariantId == null) {
            return null;
        }
        return requireOwnLiveVariant(product, productVariantId);
    }

    private ProductVariant requireOwnLiveVariant(Product product, Long productVariantId) {
        ProductVariant variant = variants.findWithValuesById(productVariantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Varijanta nije pronađena: " + productVariantId));
        if (!variant.getProduct().getId().equals(product.getId())) {
            throw new ConflictException("Varijanta ne pripada proizvodu „"
                    + product.getProductName() + "“.");
        }
        if (variant.isArchived()) {
            throw new ConflictException("Varijanta je arhivirana i ne ulazi u nove stavke.");
        }
        return variant;
    }
}
