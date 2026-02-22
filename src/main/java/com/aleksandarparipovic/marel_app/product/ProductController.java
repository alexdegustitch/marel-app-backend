package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationCountRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationListRow;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
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
