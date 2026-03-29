package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

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
    public ResponseEntity<List<PayrollAdjustmentCategory>> findAll() {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollAdjustmentCategory> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PayrollAdjustmentCategory> create(@RequestBody PayrollAdjustmentCategory entity) {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.create(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollAdjustmentCategory> update(@PathVariable Long id, @RequestBody PayrollAdjustmentCategory entity) {
        return ResponseEntity.ok(payrollAdjustmentCategoryService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollAdjustmentCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
