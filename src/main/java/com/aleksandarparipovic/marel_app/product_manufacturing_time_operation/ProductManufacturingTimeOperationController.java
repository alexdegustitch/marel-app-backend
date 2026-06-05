package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation;

import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/product-manufacturing-time-operations")
public class ProductManufacturingTimeOperationController {

    private final ProductManufacturingTimeOperationService service;

    @PostMapping
    public ResponseEntity<ProductManufacturingTimeOperationDto> create(
            @Valid @RequestBody ProductManufacturingTimeOperationCreateRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductManufacturingTimeOperationDto> update(
            @PathVariable Long id,
            @RequestBody ProductManufacturingTimeOperationUpdateRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductManufacturingTimeOperationDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @GetMapping("/by-manufacturing-time/{productManufacturingTimeId}")
    public ResponseEntity<List<ProductManufacturingTimeOperationDto>> getByProductManufacturingTimeId(
            @PathVariable Long productManufacturingTimeId) {
        return ResponseEntity.ok(service.getByProductManufacturingTimeId(productManufacturingTimeId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

