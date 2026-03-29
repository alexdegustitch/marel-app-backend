package com.aleksandarparipovic.marel_app.payroll_run_item;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-run-items")
@RequiredArgsConstructor
public class PayrollRunItemController {

    private final PayrollRunItemService payrollRunItemService;

    @GetMapping
    public ResponseEntity<List<PayrollRunItem>> findAll() {
        return ResponseEntity.ok(payrollRunItemService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunItem> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunItemService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PayrollRunItem> create(@RequestBody PayrollRunItem entity) {
        return ResponseEntity.ok(payrollRunItemService.create(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunItem> update(@PathVariable Long id, @RequestBody PayrollRunItem entity) {
        return ResponseEntity.ok(payrollRunItemService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunItemService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
