package com.aleksandarparipovic.marel_app.payroll_adjustment;

import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentResponse;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentUpdateRequest;
import jakarta.validation.Valid;
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
    public ResponseEntity<List<PayrollAdjustmentResponse>> findAll() {
        return ResponseEntity.ok(payrollAdjustmentService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollAdjustmentResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollAdjustmentService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PayrollAdjustmentResponse> create(@Valid @RequestBody PayrollAdjustmentCreateRequest request) {
        return ResponseEntity.ok(payrollAdjustmentService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollAdjustmentResponse> update(@PathVariable Long id, @RequestBody PayrollAdjustmentUpdateRequest request) {
        return ResponseEntity.ok(payrollAdjustmentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollAdjustmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
