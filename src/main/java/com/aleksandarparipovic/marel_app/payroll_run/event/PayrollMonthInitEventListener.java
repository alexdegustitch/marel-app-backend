package com.aleksandarparipovic.marel_app.payroll_run.event;

import com.aleksandarparipovic.marel_app.payroll_run.PayrollRunInitializationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link PayrollMonthInitEvent} after the HTTP transaction commits,
 * then delegates to the async initialization service.
 *
 * <p>Using AFTER_COMMIT ensures employee records are fully persisted and visible
 * to the async transaction before initialization starts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollMonthInitEventListener {

    private final PayrollRunInitializationService initializationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPayrollMonthInit(PayrollMonthInitEvent event) {
        log.info("[PayrollInit] Received init event for {}/{}, dispatching async...", event.year(), event.month());
        initializationService.initializePayrollMonth(event);
    }
}

