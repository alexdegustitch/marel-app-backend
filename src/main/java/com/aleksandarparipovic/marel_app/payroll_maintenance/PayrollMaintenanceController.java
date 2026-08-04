package com.aleksandarparipovic.marel_app.payroll_maintenance;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payroll-maintenance")
@RequiredArgsConstructor
public class PayrollMaintenanceController {

    private final PayrollMaintenanceService payrollMaintenanceService;
    private final PayrollConfigurationValidationService configurationValidationService;

    /**
     * What is wrong with the payroll configuration, before anybody opens a month.
     *
     * <p>GET because it reads: it changes nothing and refuses nothing. The rules
     * are enforced where they must be — the migration raises on an incomplete
     * matrix and the resolver throws at calculation time — and this is the report
     * that finds the gap first, for the whole factory at once, instead of one
     * employee at a time as somebody opens their payroll.
     */
    @GetMapping("/configuration-report")
    @PreAuthorize("@perm.has('PAYROLL_MAINTENANCE_RECALCULATE')")
    public ResponseEntity<PayrollConfigurationReport> configurationReport() {
        return ResponseEntity.ok(configurationValidationService.validate());
    }

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
