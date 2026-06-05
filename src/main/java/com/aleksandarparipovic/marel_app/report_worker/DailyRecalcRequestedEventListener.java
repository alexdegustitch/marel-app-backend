package com.aleksandarparipovic.marel_app.report_worker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DailyRecalcRequestedEventListener {

    private final RecalcWorkerWakeSignal wakeSignal;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecalcRequested(DailyRecalcRequestedEvent event) {
        wakeSignal.signalAll();
    }
}

