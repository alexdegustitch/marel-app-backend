package com.aleksandarparipovic.marel_app.payroll_run;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-runs")
@RequiredArgsConstructor
public class PayrollRunController {

    private final PayrollRunService payrollRunService;

    @GetMapping
    public ResponseEntity<List<PayrollRun>> findAll() {
        return ResponseEntity.ok(payrollRunService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollRun> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PayrollRun> create(@RequestBody PayrollRun entity) {
        return ResponseEntity.ok(payrollRunService.create(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRun> update(@PathVariable Long id, @RequestBody PayrollRun entity) {
        return ResponseEntity.ok(payrollRunService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
