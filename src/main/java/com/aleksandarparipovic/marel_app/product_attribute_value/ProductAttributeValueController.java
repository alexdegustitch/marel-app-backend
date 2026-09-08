package com.aleksandarparipovic.marel_app.product_attribute_value;

import com.aleksandarparipovic.marel_app.product_attribute_value.dto.ProductAttributeValueDto;
import com.aleksandarparipovic.marel_app.product_attribute_value.dto.ProductAttributeValuesSaveRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A product's spec values, addressed from the product
 * ({@code /api/products/{productId}/attributes}).
 *
 * <p>GET returns the form — every active attribute of the product's type with
 * its current value. PUT replaces the whole set in one call. No matcher of its
 * own in {@code SecurityConfig} — {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/products/{productId}/attributes")
@RequiredArgsConstructor
public class ProductAttributeValueController {

    private final ProductAttributeValueService service;

    @GetMapping
    public ResponseEntity<List<ProductAttributeValueDto>> get(@PathVariable Long productId) {
        return ResponseEntity.ok(service.getForProduct(productId));
    }

    @PutMapping
    public ResponseEntity<List<ProductAttributeValueDto>> save(
            @PathVariable Long productId,
            @Valid @RequestBody ProductAttributeValuesSaveRequest request
    ) {
        return ResponseEntity.ok(service.saveForProduct(productId, request));
    }
}
