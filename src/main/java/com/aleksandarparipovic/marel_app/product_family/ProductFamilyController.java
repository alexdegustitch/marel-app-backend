package com.aleksandarparipovic.marel_app.product_family;

import com.aleksandarparipovic.marel_app.common.ArchiveConfirmationRequest;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyCreateRequest;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyDto;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyOptionDto;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The top level of the catalogue: families of products.
 *
 * <p>No matcher of its own in {@code SecurityConfig}; it falls to
 * {@code anyRequest().authenticated()}, so anybody signed in may read and
 * maintain it — the same footing as the customer and product lists.
 */
@RestController
@RequestMapping("/api/product-families")
@RequiredArgsConstructor
public class ProductFamilyController {

    private final ProductFamilyService service;

    @PostMapping
    public ResponseEntity<ProductFamilyDto> create(@Valid @RequestBody ProductFamilyCreateRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    /** One box searches name and description together — see ProductFamilySpecifications. */
    @GetMapping
    public ResponseEntity<Page<ProductFamilyDto>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(defaultValue = "sortOrder") String sortBy
    ) {
        return ResponseEntity.ok(service.search(query, active, page, size, direction, sortBy));
    }

    /** For pickers: the active families only, in list order. */
    @GetMapping("/options")
    public ResponseEntity<List<ProductFamilyOptionDto>> options() {
        return ResponseEntity.ok(service.options());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductFamilyDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductFamilyDto> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductFamilyUpdateRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /**
     * Archive, signed with the caller's password. Not a delete — the types filed
     * under this family keep pointing at it, and restore undoes it.
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
