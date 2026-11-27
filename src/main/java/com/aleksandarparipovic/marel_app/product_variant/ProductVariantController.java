package com.aleksandarparipovic.marel_app.product_variant;

import com.aleksandarparipovic.marel_app.product_variant.dto.ProductVariantDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The variants of a product. Listing and creation hang off the product (so the
 * catalogue's read-open / PRODUCT_MANAGE-write split applies by URL); a single
 * variant is addressed by its own id under {@code /api/product-variants}, which
 * {@code SecurityConfig} gates the same way.
 */
@RestController
@RequiredArgsConstructor
public class ProductVariantController {

    private final ProductVariantService service;

    @GetMapping("/api/products/{productId}/variants")
    public ResponseEntity<List<ProductVariantDto>> list(
            @PathVariable Long productId,
            @RequestParam(name = "active", required = false, defaultValue = "false") boolean onlyActive
    ) {
        return ResponseEntity.ok(service.listForProduct(productId, onlyActive));
    }

    /**
     * The variants that would be left without a value if this attribute became
     * required — the šifarnik reads it before flipping "obavezan" on.
     */
    @GetMapping("/api/variant-attributes/{attributeId}/variants-missing-value")
    public ResponseEntity<List<VariantMissingValueRow>> variantsMissingValue(@PathVariable Long attributeId) {
        return ResponseEntity.ok(service.listMissingValueForAttribute(attributeId));
    }

    @PostMapping("/api/products/{productId}/variants")
    public ResponseEntity<ProductVariantDto> create(
            @PathVariable Long productId,
            @Valid @RequestBody ProductVariantCreateRequest request
    ) {
        return ResponseEntity.ok(service.create(productId, request));
    }

    @PatchMapping("/api/product-variants/{id}")
    public ResponseEntity<ProductVariantDto> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductVariantUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** Archive, not delete — lines that ordered it keep pointing at it. */
    @DeleteMapping("/api/product-variants/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/product-variants/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }
}
