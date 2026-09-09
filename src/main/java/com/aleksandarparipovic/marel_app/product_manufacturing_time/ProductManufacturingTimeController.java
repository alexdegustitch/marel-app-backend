package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeStatsRow;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/product-manufacturing-times")
public class ProductManufacturingTimeController {

    private final ProductManufacturingTimeService service;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;

    @PostMapping
    public ResponseEntity<ProductManufacturingTimeDto> create(
            @Valid @RequestBody ProductManufacturingTimeCreateRequest req,
            Authentication authentication) {
        return ResponseEntity.ok(service.create(req, authentication));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductManufacturingTimeDto> update(
            @PathVariable Long id,
            @RequestBody ProductManufacturingTimeUpdateRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductManufacturingTimeDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @GetMapping("/my")
    public ResponseEntity<List<ProductManufacturingTimeDto>> getForCurrentUser(Authentication authentication) {
        return ResponseEntity.ok(service.getForCurrentUser(authentication));
    }

    /**
     * Records that answer a request — shared, because the workflow that produced
     * them is shared. The `/my` list above remains one person's own.
     */
    @GetMapping("/from-requests")
    public ResponseEntity<List<ProductManufacturingTimeDto>> getFromRequests() {
        return ResponseEntity.ok(service.getAnsweringRequests());
    }

    /**
     * The `/my` list as one server-searched page — a READ carried by POST because
     * it brings the paging, sorting and filter payload with it.
     */
    @PostMapping("/my/search")
    public Page<ProductManufacturingTimeDto> searchMine(
            @RequestBody SearchRequest request,
            Authentication authentication) {
        return service.searchMine(request, authentication);
    }

    /** The `/from-requests` list under the same server-side controls. */
    @PostMapping("/from-requests/search")
    public Page<ProductManufacturingTimeDto> searchFromRequests(@RequestBody SearchRequest request) {
        return service.searchFromRequests(request);
    }

    /** The page's KPI figures — one request for the whole board. */
    @GetMapping("/stats")
    public ResponseEntity<ProductManufacturingTimeStatsRow> getStats() {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long restrictTo =
                permissionService.hasPermission(AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL)
                        ? null
                        : currentUserId;
        return ResponseEntity.ok(service.getStats(currentUserId, restrictTo));
    }

    @GetMapping("/by-product/{productId}")
    public ResponseEntity<List<ProductManufacturingTimeDto>> getByProductId(@PathVariable Long productId) {
        return ResponseEntity.ok(service.getByProductId(productId));
    }

    @GetMapping("/by-product/{productId}/range")
    public ResponseEntity<List<ProductManufacturingTimeDto>> getByProductIdAndDateRange(
            @PathVariable Long productId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getByProductIdAndDateRange(productId, from, to));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
