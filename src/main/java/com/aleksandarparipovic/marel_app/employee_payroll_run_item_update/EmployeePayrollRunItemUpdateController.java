package com.aleksandarparipovic.marel_app.employee_payroll_run_item_update;

import com.aleksandarparipovic.marel_app.employee_payroll_run_item_update.dto.EmployeePayrollRunItemUpdateDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payroll-run-item-updates")
public class EmployeePayrollRunItemUpdateController {

    private final EmployeePayrollRunItemUpdateService service;

    @GetMapping
    public ResponseEntity<List<EmployeePayrollRunItemUpdateDto>> getByPayrollRunItemId(
            @RequestParam Long payrollRunItemId) {
        return ResponseEntity.ok(service.getByPayrollRunItemId(payrollRunItemId));
    }

    @GetMapping("/by-user")
    public ResponseEntity<List<EmployeePayrollRunItemUpdateDto>> getByUserId(
            @RequestParam Long userId) {
        return ResponseEntity.ok(service.getByUserId(userId));
    }

    @PostMapping("/upsert")
    public ResponseEntity<Void> upsertActivity(
            @RequestParam Long payrollRunItemId,
            @RequestParam Long userId) {
        service.upsertActivity(payrollRunItemId, userId);
        return ResponseEntity.ok().build();
    }
}

