package com.aleksandarparipovic.marel_app.variant_attribute;

import com.aleksandarparipovic.marel_app.variant_attribute.dto.VariantAttributeDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The variant-attribute šifarnik. Listing and creation hang off the type;
 * a single attribute or option is addressed by its own id.
 *
 * <p>No matcher of its own in {@code SecurityConfig} — {@code anyRequest().authenticated()},
 * exactly like the descriptive product-type attributes beside it.
 */
@RestController
@RequiredArgsConstructor
public class VariantAttributeController {

    private final VariantAttributeService service;

    // ── attributes of a type ────────────────────────────────────────────────

    @GetMapping("/api/product-types/{typeId}/variant-attributes")
    public ResponseEntity<List<VariantAttributeDto>> list(@PathVariable Long typeId) {
        return ResponseEntity.ok(service.listForType(typeId));
    }

    @PostMapping("/api/product-types/{typeId}/variant-attributes")
    public ResponseEntity<VariantAttributeDto> create(
            @PathVariable Long typeId,
            @Valid @RequestBody VariantAttributeCreateRequest request
    ) {
        return ResponseEntity.ok(service.create(typeId, request));
    }

    @PatchMapping("/api/variant-attributes/{id}")
    public ResponseEntity<VariantAttributeDto> update(
            @PathVariable Long id,
            @Valid @RequestBody VariantAttributeUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** Archive, not delete — variants and bindings that reference it stay valid. */
    @DeleteMapping("/api/variant-attributes/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/variant-attributes/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    // ── options of an attribute ─────────────────────────────────────────────

    @GetMapping("/api/variant-attributes/{id}/options")
    public ResponseEntity<List<VariantAttributeOptionDto>> listOptions(@PathVariable Long id) {
        return ResponseEntity.ok(service.listOptions(id));
    }

    @PostMapping("/api/variant-attributes/{id}/options")
    public ResponseEntity<VariantAttributeOptionDto> createOption(
            @PathVariable Long id,
            @Valid @RequestBody VariantAttributeOptionCreateRequest request
    ) {
        return ResponseEntity.ok(service.createOption(id, request));
    }

    @PatchMapping("/api/variant-attribute-options/{id}")
    public ResponseEntity<VariantAttributeOptionDto> updateOption(
            @PathVariable Long id,
            @Valid @RequestBody VariantAttributeOptionUpdateRequest request
    ) {
        return ResponseEntity.ok(service.updateOption(id, request));
    }

    @DeleteMapping("/api/variant-attribute-options/{id}")
    public ResponseEntity<Void> archiveOption(@PathVariable Long id) {
        service.archiveOption(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/variant-attribute-options/{id}/restore")
    public ResponseEntity<Void> restoreOption(@PathVariable Long id) {
        service.restoreOption(id);
        return ResponseEntity.noContent().build();
    }
}
