package com.aleksandarparipovic.marel_app.payroll_maintenance;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Push every payroll item through the calculator once.
 *
 * <p>WHY THIS EXISTS. Recalculation is lazy: an item is recomputed when somebody
 * opens it, and nothing sweeps the rest. After a change to the model — moving the
 * override state from payroll_run_items onto payroll_adjustments — most items
 * therefore still hold what the old code left behind, and the diagnostic that
 * compares the two has nothing to compare. Doing it by hand means opening every
 * employee's payroll for every month.
 *
 * <p>It calls exactly what the screen calls. No second calculation path: a
 * maintenance job that computed things its own way would prove nothing about what
 * users see.
 *
 * <p>DELIBERATELY NOT TRANSACTIONAL AT THIS LEVEL. Each item recalculates in its
 * own transaction, through the injected service's proxy, so one bad item fails
 * alone instead of rolling back a thousand good ones — and the report says which.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollMaintenanceService {

    /** Enough to see the shape of a problem without returning a thousand lines. */
    private static final int MAX_REPORTED_FAILURES = 20;

    private final PayrollRunItemService payrollRunItemService;

    public RecalculationReport recalculateAll() {
        int flagged = payrollRunItemService.flagAllForRecalculation();
        List<Long> ids = payrollRunItemService.recalculableItemIds();

        log.info("Payroll maintenance sweep starting: {} item(s) flagged, {} to visit", flagged, ids.size());

        List<RecalculationReport.Failure> failures = new ArrayList<>();
        int recalculated = 0;

        for (Long id : ids) {
            try {
                payrollRunItemService.getForPayrollAccess(id);
                recalculated++;
            } catch (RuntimeException ex) {
                // Recorded, not rethrown. A single item with an incomplete
                // configuration must not stop the other thousand from being
                // brought up to date — and the whole point of the sweep is to find
                // out which ones cannot be.
                log.warn("Payroll maintenance: item {} failed to recalculate: {}", id, ex.getMessage());
                if (failures.size() < MAX_REPORTED_FAILURES) {
                    failures.add(new RecalculationReport.Failure(id, ex.getMessage()));
                }
            }
        }

        int failed = ids.size() - recalculated;
        log.info("Payroll maintenance sweep finished: {} recalculated, {} failed", recalculated, failed);

        return new RecalculationReport(ids.size(), recalculated, failed, failures);
    }
}
