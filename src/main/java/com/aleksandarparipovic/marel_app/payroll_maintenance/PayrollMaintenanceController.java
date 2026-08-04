package com.aleksandarparipovic.marel_app.payroll_maintenance;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payroll-maintenance")
@RequiredArgsConstructor
public class PayrollMaintenanceController {

    private final PayrollMaintenanceService payrollMaintenanceService;

    /**
     * Recalculate every unlocked payroll item.
     *
     * <p>POST because it writes to a thousand rows — a GET that recalculates the
     * whole payroll is a link somebody's browser can prefetch.
     *
     * <p>Synchronous on purpose: the caller waits and reads what happened. Handing
     * back a job id would mean building a job model to run this once or twice in
     * the life of the migration.
     */
    @PostMapping("/recalculate-all")
    @PreAuthorize("@perm.has('PAYROLL_MAINTENANCE_RECALCULATE')")
    public ResponseEntity<RecalculationReport> recalculateAll() {
        return ResponseEntity.ok(payrollMaintenanceService.recalculateAll());
    }
}
