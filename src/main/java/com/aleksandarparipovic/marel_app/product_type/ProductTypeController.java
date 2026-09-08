package com.aleksandarparipovic.marel_app.product_type;

import com.aleksandarparipovic.marel_app.common.ArchiveConfirmationRequest;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeCreateRequest;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeDto;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeOptionDto;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The middle level of the catalogue: types of product within a family.
 *
 * <p>No matcher of its own in {@code SecurityConfig}; it falls to
 * {@code anyRequest().authenticated()}, the same footing as families and products.
 */
@RestController
@RequestMapping("/api/product-types")
@RequiredArgsConstructor
public class ProductTypeController {

    private final ProductTypeService service;

    @PostMapping
    public ResponseEntity<ProductTypeDto> create(@Valid @RequestBody ProductTypeCreateRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    /**
     * One box searches name, code and description together. {@code familyId}
     * narrows the list to one family — see ProductTypeSpecifications.
     */
    @GetMapping
    public ResponseEntity<Page<ProductTypeDto>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Long familyId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(defaultValue = "sortOrder") String sortBy
    ) {
        return ResponseEntity.ok(service.search(query, familyId, active, page, size, direction, sortBy));
    }

    /**
     * For pickers: the active types only. {@code familyId} narrows to the types
     * under one family — how the product form offers only the relevant types.
     */
    @GetMapping("/options")
    public ResponseEntity<List<ProductTypeOptionDto>> options(
            @RequestParam(required = false) Long familyId
    ) {
        return ResponseEntity.ok(service.options(familyId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductTypeDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductTypeDto> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductTypeUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /**
     * Archive, signed with the caller's password. Not a delete — the products of
     * this type keep pointing at it, and restore undoes it.
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(
            @PathVariable Long id,
            @Valid @RequestBody ArchiveConfirmationRequest request,
            Authentication authentication
    ) {
        service.deactivate(id, request.getPassword(), authentication);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }
}
