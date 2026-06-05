package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeCreateRequest;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeDto;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.dto.ProductManufacturingTimeUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/by-operation/{operationId}")
    public ResponseEntity<List<ProductManufacturingTimeDto>> getByOperationId(@PathVariable Long operationId) {
        return ResponseEntity.ok(service.getByOperationId(operationId));
    }

    @GetMapping("/by-operation/{operationId}/range")
    public ResponseEntity<List<ProductManufacturingTimeDto>> getByOperationIdAndDateRange(
            @PathVariable Long operationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getByOperationIdAndDateRange(operationId, from, to));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

