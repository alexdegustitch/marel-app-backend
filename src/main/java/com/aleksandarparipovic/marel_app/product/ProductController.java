package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.operation.dto.OperationDto;
import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductProductionOrderRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductSampleOrderRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductCreateRequest;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationListRow;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/{productId}/operations")
    public ResponseEntity<List<OperationDto>> getProductOperations(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProductOperations(productId));
    }

    @GetMapping("/{productId}/production-orders")
    public ResponseEntity<List<ProductProductionOrderRow>> getProductProductionOrders(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProductProductionOrders(productId));
    }

    @GetMapping("/{productId}/sample-orders")
    public ResponseEntity<List<ProductSampleOrderRow>> getProductSampleOrders(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProductSampleOrders(productId));
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
