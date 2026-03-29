package com.aleksandarparipovic.marel_app.payroll_run_item_category;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-run-item-categories")
@RequiredArgsConstructor
public class PayrollRunItemCategoryController {

    private final PayrollRunItemCategoryService payrollRunItemCategoryService;

    @GetMapping
    public ResponseEntity<List<PayrollRunItemCategory>> findAll() {
        return ResponseEntity.ok(payrollRunItemCategoryService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunItemCategory> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunItemCategoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PayrollRunItemCategory> create(@RequestBody PayrollRunItemCategory entity) {
        return ResponseEntity.ok(payrollRunItemCategoryService.create(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunItemCategory> update(@PathVariable Long id, @RequestBody PayrollRunItemCategory entity) {
        return ResponseEntity.ok(payrollRunItemCategoryService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunItemCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
