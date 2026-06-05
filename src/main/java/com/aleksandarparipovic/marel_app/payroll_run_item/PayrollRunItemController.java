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

    /**
     * Raw lookup — does NOT trigger version-based recalculation.
     * Use {@code GET /{id}/payroll} for version-aware access.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunItem> findById(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunItemService.findById(id));
    }

    /**
     * Payroll-aware access for a single item.
     * <p>
     * Automatically recalculates the item if {@code monthly_reports.version}
     * has advanced past {@code based_on_version}, <b>unless</b> the item is LOCKED.
     *
     * <pre>
     * GET /api/payroll-run-items/{id}/payroll
     * </pre>
     */
    @GetMapping("/{id}/payroll")
    public ResponseEntity<PayrollRunItem> getForPayrollAccess(@PathVariable Long id) {
        return ResponseEntity.ok(payrollRunItemService.getForPayrollAccess(id));
    }

    /**
     * Returns all items for a payroll run, recalculating any that are stale.
     * LOCKED items are returned as-is.
     *
     * <pre>
     * GET /api/payroll-run-items/by-run/{runId}
     * </pre>
     */
    @GetMapping("/by-run/{runId}")
    public ResponseEntity<List<PayrollRunItem>> getForPayrollRun(@PathVariable Long runId) {
        return ResponseEntity.ok(payrollRunItemService.getForPayrollRun(runId));
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
