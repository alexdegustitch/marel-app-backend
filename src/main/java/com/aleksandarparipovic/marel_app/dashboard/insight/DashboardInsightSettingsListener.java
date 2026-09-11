package com.aleksandarparipovic.marel_app.dashboard.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Recomputes the snapshot when a board threshold is tuned.
 *
 * <p>AFTER_COMMIT, because the recompute must read the value that was saved —
 * inside the transaction it would still see the old one. Async, because the
 * save request should return when the save is done: the recompute is heavy,
 * belongs to nobody's click, and its failure mode (the job's own try/catch)
 * is "the board says which day it is from", not an error on the settings form.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardInsightSettingsListener {

    private final DashboardInsightJob insightJob;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettingsChanged(DashboardSettingsChangedEvent event) {
        log.info("[DashboardInsight] Podešavanje {} promenjeno — analitika se preračunava.",
                event.settingKey());
        insightJob.recomputeNow();
    }
}
