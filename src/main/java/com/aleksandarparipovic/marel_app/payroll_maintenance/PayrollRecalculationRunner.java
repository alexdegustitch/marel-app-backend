package com.aleksandarparipovic.marel_app.payroll_maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the payroll recalculation sweep once, at startup, when explicitly asked.
 *
 * <p>WHY A STARTUP FLAG AND NOT ONLY THE ENDPOINT. This is a migration chore, run
 * once or twice in the life of the change: every payroll item has to go through
 * the component calculator so the diagnostic comparing the item columns against
 * the adjustment lines has something to compare. Doing it over HTTP means somebody
 * authenticating first, and an operator's password is not a thing to pass around
 * for a maintenance task. The flag needs nothing but the flag.
 *
 * <p>OFF UNLESS SET. {@code @ConditionalOnProperty} without {@code matchIfMissing}
 * means the bean does not exist at all unless the property is literally "true", so
 * an ordinary start — and every test — cannot trip it.
 *
 * <p>It does not fail startup. A sweep that cannot finish is a thing to read about
 * in the log, not a reason the application refuses to come up; the report says
 * which items failed and the endpoint is still there to retry them.
 */
@Component
@ConditionalOnProperty(name = "app.payroll.recalculate-on-startup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PayrollRecalculationRunner implements ApplicationRunner {

    private final PayrollMaintenanceService payrollMaintenanceService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== app.payroll.recalculate-on-startup is set — sweeping every payroll item ===");
        try {
            RecalculationReport report = payrollMaintenanceService.recalculateAll();
            log.info("=== SWEEP DONE: visited={} recalculated={} failed={} ===",
                    report.visited(), report.recalculated(), report.failed());
            report.failures().forEach(f ->
                    log.warn("=== SWEEP FAILURE: item {} — {}", f.payrollRunItemId(), f.reason()));
            if (report.failed() > 0) {
                log.warn("=== {} item(s) could not be recalculated. Do not drop any column "
                        + "until these are understood. ===", report.failed());
            }
        } catch (RuntimeException ex) {
            log.error("=== SWEEP ABORTED: {} ===", ex.getMessage(), ex);
        }
    }
}
