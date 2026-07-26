package com.aleksandarparipovic.marel_app.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled poller that drains the transactional outbox.
 *
 * <p>Holds no transactional logic of its own — see {@link OutboxBatchProcessor}
 * for why that must live in a separate bean, and why claiming and processing are
 * separate transactions. Mirrors the existing {@code DailyReportWorker} pattern:
 * several instances may run this and each claims a disjoint batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventWorker {

    private final OutboxBatchProcessor processor;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void drain() {
        try {
            for (Long eventId : processor.claimBatch()) {
                try {
                    processor.processOne(eventId);
                } catch (Exception ex) {
                    // Recorded on its own row, in its own transaction, so one bad
                    // event never stops the rest of the batch.
                    processor.recordFailure(eventId, ex);
                }
            }
        } catch (Exception ex) {
            // A worker that dies stops every notification in the system, so the loop
            // itself never propagates.
            log.error("[OutboxEventWorker] Batch processing failed", ex);
        }
    }
}
