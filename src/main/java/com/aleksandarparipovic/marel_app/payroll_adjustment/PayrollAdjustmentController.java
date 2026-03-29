package com.aleksandarparipovic.marel_app.payroll_adjustment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-adjustments")
@RequiredArgsConstructor
public class PayrollAdjustmentController {

    private final PayrollAdjustmentService payrollAdjustmentService;

    @GetMapping
    public ResponseEntity<List<PayrollAdjustment>> findAll() {
        return ResponseEntity.ok(payrollAdjustmentService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollAdjustment> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollAdjustmentService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PayrollAdjustment> create(@RequestBody PayrollAdjustment entity) {
        return ResponseEntity.ok(payrollAdjustmentService.create(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollAdjustment> update(@PathVariable Long id, @RequestBody PayrollAdjustment entity) {
        return ResponseEntity.ok(payrollAdjustmentService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollAdjustmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
