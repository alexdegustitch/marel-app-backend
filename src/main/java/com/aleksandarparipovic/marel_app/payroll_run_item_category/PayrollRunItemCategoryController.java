package com.aleksandarparipovic.marel_app.payroll_run_item_category;

import com.aleksandarparipovic.marel_app.payroll_run_item_category.dto.PayrollRunItemCategoryCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.dto.PayrollRunItemCategoryResponse;
import jakarta.validation.Valid;
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
    public ResponseEntity<List<PayrollRunItemCategoryResponse>> findAll() {
        return ResponseEntity.ok(payrollRunItemCategoryService.findAll().stream().map(PayrollRunItemCategoryResponse::new).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunItemCategoryResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunItemCategoryResponse(payrollRunItemCategoryService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<PayrollRunItemCategoryResponse> create(@Valid @RequestBody PayrollRunItemCategoryCreateRequest request) {
        return ResponseEntity.ok(new PayrollRunItemCategoryResponse(payrollRunItemCategoryService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunItemCategoryResponse> update(@PathVariable Long id, @RequestBody PayrollRunItemCategory entity) {
        return ResponseEntity.ok(new PayrollRunItemCategoryResponse(payrollRunItemCategoryService.update(id, entity)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunItemCategoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
