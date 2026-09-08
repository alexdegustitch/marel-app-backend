package com.aleksandarparipovic.marel_app.product_type_operation;

import com.aleksandarparipovic.marel_app.product_type_operation.dto.ProductTypeOperationCreateRequest;
import com.aleksandarparipovic.marel_app.product_type_operation.dto.ProductTypeOperationDto;
import com.aleksandarparipovic.marel_app.product_type_operation.dto.ProductTypeOperationUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A product type's operation TEMPLATE. Listing and creation hang off the type
 * ({@code /api/product-types/{typeId}/operations}); updating and deactivating a
 * single template operation address it directly by its own id — the same shape
 * as {@code ProductTypeAttributeController}.
 *
 * <p>No matcher of its own in {@code SecurityConfig} — {@code anyRequest().authenticated()},
 * like the attributes. The actual write to the catalogue — copying a template onto
 * a product — lives under {@code /api/operations/**} and stays gated by
 * {@code OPERATION_MANAGE}.
 */
@RestController
@RequiredArgsConstructor
public class ProductTypeOperationController {

    private final ProductTypeOperationService service;

    @GetMapping("/api/product-types/{typeId}/operations")
    public ResponseEntity<List<ProductTypeOperationDto>> list(@PathVariable Long typeId) {
        return ResponseEntity.ok(service.listForType(typeId));
    }

    @PostMapping("/api/product-types/{typeId}/operations")
    public ResponseEntity<ProductTypeOperationDto> create(
            @PathVariable Long typeId,
            @Valid @RequestBody ProductTypeOperationCreateRequest request
    ) {
        return ResponseEntity.ok(service.create(typeId, request));
    }

    @PatchMapping("/api/product-type-operations/{id}")
    public ResponseEntity<ProductTypeOperationDto> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductTypeOperationUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** Deactivate. Not a delete — products already copied from this template survive. */
    @DeleteMapping("/api/product-type-operations/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/product-type-operations/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }
}
