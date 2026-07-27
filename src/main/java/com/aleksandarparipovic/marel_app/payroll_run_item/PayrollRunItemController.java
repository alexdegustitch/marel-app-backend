package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemDetailResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.RecentPayrollSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemActivityDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-run-items")
@RequiredArgsConstructor
public class PayrollRunItemController {

    private final PayrollRunItemService payrollRunItemService;

    @GetMapping("/last-activity")
    public ResponseEntity<List<PayrollRunItemActivityDto>> getLastActivity(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(payrollRunItemService.getLastActivityByMonth(year, month));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<RecentPayrollSummaryDto>> getRecentByEmployee(
            @RequestParam Long employeeId,
            @RequestParam(defaultValue = "3") int size) {
        return ResponseEntity.ok(payrollRunItemService.getRecentByEmployee(employeeId, size));
    }

    @GetMapping
    public ResponseEntity<List<PayrollRunItemResponse>> findAll() {
        return ResponseEntity.ok(payrollRunItemService.findAll().stream().map(PayrollRunItemResponse::new).toList());
    }

    /**
     * Raw lookup — does NOT trigger version-based recalculation.
     * Use {@code GET /{id}/payroll} for version-aware access.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunItemResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.findById(id)));
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
    public ResponseEntity<PayrollRunItemResponse> getForPayrollAccess(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.getForPayrollAccess(id)));
    }

    /**
     * @param locale optional override for the document language. Omitted, the
     *               employee's own preferred_locale is used — a payslip is a
     *               document about the employee, not about the clerk opening it.
     *               It selects display names only; every amount is identical in
     *               every locale.
     */
    @GetMapping("/by-monthly-report/{monthlyReportId}/details")
    public ResponseEntity<PayrollRunItemDetailResponse> getDetails(
            @PathVariable Long monthlyReportId,
            @RequestParam(required = false) String locale) {
        return ResponseEntity.ok(payrollRunItemService.getDetails(monthlyReportId, locale));
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
    public ResponseEntity<List<PayrollRunItemResponse>> getForPayrollRun(@PathVariable Long runId) {
        return ResponseEntity.ok(payrollRunItemService.getForPayrollRun(runId).stream().map(PayrollRunItemResponse::new).toList());
    }

    @PostMapping
    public ResponseEntity<PayrollRunItemResponse> create(@Valid @RequestBody PayrollRunItemCreateRequest request) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.create(request)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PayrollRunItemDetailResponse> patch(
            @PathVariable Long id,
            @RequestBody PayrollRunItemPatchRequest request) {
        return ResponseEntity.ok(payrollRunItemService.patch(id, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunItemResponse> update(@PathVariable Long id, @RequestBody PayrollRunItem entity) {
        return ResponseEntity.ok(new PayrollRunItemResponse(payrollRunItemService.update(id, entity)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunItemService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
