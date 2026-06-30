package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto.PayrollAdjustmentCategoryCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto.PayrollAdjustmentCategoryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-adjustment-categories")
@RequiredArgsConstructor
public class PayrollAdjustmentCategoryController {

    private final PayrollAdjustmentCategoryService payrollAdjustmentCategoryService;

    @GetMapping
    public ResponseEntity<List<PayrollAdjustmentCategoryResponse>> findAll() {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollAdjustmentCategoryResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PayrollAdjustmentCategoryResponse> create(@Valid @RequestBody PayrollAdjustmentCategoryCreateRequest request) {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollAdjustmentCategoryResponse> update(@PathVariable Long id, @Valid @RequestBody PayrollAdjustmentCategoryCreateRequest request) {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollAdjustmentCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
