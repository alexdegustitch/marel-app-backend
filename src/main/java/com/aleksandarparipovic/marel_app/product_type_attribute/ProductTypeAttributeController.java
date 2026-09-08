package com.aleksandarparipovic.marel_app.product_type_attribute;

import com.aleksandarparipovic.marel_app.product_type_attribute.dto.ProductTypeAttributeCreateRequest;
import com.aleksandarparipovic.marel_app.product_type_attribute.dto.ProductTypeAttributeDto;
import com.aleksandarparipovic.marel_app.product_type_attribute.dto.ProductTypeAttributeUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The spec schema of a type. Listing and creation hang off the type
 * ({@code /api/product-types/{typeId}/attributes}); updating and deactivating a
 * single attribute address it directly by its own id.
 *
 * <p>No matcher of its own in {@code SecurityConfig} — {@code anyRequest().authenticated()}.
 */
@RestController
@RequiredArgsConstructor
public class ProductTypeAttributeController {

    private final ProductTypeAttributeService service;

    @GetMapping("/api/product-types/{typeId}/attributes")
    public ResponseEntity<List<ProductTypeAttributeDto>> list(@PathVariable Long typeId) {
        return ResponseEntity.ok(service.listForType(typeId));
    }

    @PostMapping("/api/product-types/{typeId}/attributes")
    public ResponseEntity<ProductTypeAttributeDto> create(
            @PathVariable Long typeId,
            @Valid @RequestBody ProductTypeAttributeCreateRequest request
    ) {
        return ResponseEntity.ok(service.create(typeId, request));
    }

    @PatchMapping("/api/product-type-attributes/{id}")
    public ResponseEntity<ProductTypeAttributeDto> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductTypeAttributeUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** Deactivate. Not a delete — the values recorded against this attribute survive. */
    @DeleteMapping("/api/product-type-attributes/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/product-type-attributes/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }
}
