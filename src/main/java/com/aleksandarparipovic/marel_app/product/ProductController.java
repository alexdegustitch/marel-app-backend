package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.common.ArchiveConfirmationRequest;
import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductProductionOrderRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductCreateRequest;
import com.aleksandarparipovic.marel_app.product.dto.ProductUpdateRequest;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import com.aleksandarparipovic.marel_app.product.dto.ProductStatsRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationListRow;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductBaseRow> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.ok(productService.createProduct(request));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductBaseRow> getProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProduct(productId));
    }

    /** Edit a product's catalogue placement and fields (type, catalog number, subtype…). */
    @PatchMapping("/{productId}")
    public ResponseEntity<ProductBaseRow> updateProduct(
            @PathVariable Long productId,
            @Valid @RequestBody ProductUpdateRequest request
    ) {
        return ResponseEntity.ok(productService.updateProduct(productId, request));
    }

    @GetMapping("/{productId}/operations")
    public ResponseEntity<List<OperationDto>> getProductOperations(
            @PathVariable Long productId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String direction
    ) {
        return ResponseEntity.ok(
                productService.getProductOperations(productId, query, sortBy, direction));
    }

    @GetMapping("/{productId}/production-orders")
    public ResponseEntity<List<ProductProductionOrderRow>> getProductProductionOrders(
            @PathVariable Long productId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String direction
    ) {
        return ResponseEntity.ok(
                productService.getProductProductionOrders(productId, query, sortBy, direction));
    }

    @GetMapping("/{productId}/sample-orders")
    public ResponseEntity<List<ProductSampleOrderRow>> getProductSampleOrders(
            @PathVariable Long productId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String direction
    ) {
        return ResponseEntity.ok(
                productService.getProductSampleOrders(productId, query, sortBy, direction));
    }

    /** Why the product cannot be archived right now; an empty list means it can. */
    @GetMapping("/{productId}/archive-blockers")
    public ResponseEntity<List<String>> getArchiveBlockers(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getArchiveBlockers(productId));
    }

    /** Archive, signed with the caller's re-typed password. Takes the live operations with it. */
    @PostMapping("/{productId}/archive")
    public ResponseEntity<Void> archiveProduct(
            @PathVariable Long productId,
            @Valid @RequestBody ArchiveConfirmationRequest request,
            Authentication authentication
    ) {
        productService.archiveProduct(productId, request.getPassword(), authentication);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{productId}/restore")
    public ResponseEntity<Void> restoreProduct(@PathVariable Long productId) {
        productService.restoreProduct(productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<ProductStatsRow> getStats() {
        return ResponseEntity.ok(productService.getStats());
    }

    @PostMapping("/search-all")
    public Page<ProductWithOperationListRow> searchAll(@RequestBody SearchRequest request){
        return productService.searchAll(request);
    }

    @GetMapping("/active-products")
    @Cacheable("product-options")
    public ResponseEntity<List<ProductOptionDto>> getAllProducts(){
        List<ProductOptionDto> products = productService.getAllProducts();
        return ResponseEntity.ok(products);
    }

}
