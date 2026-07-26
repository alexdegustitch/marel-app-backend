package com.aleksandarparipovic.marel_app.outbox;

import com.aleksandarparipovic.marel_app.common.ErrorSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The transactional half of outbox draining, in three short, independent phases.
 *
 * <p>Why phases rather than one transaction around the batch — two bugs this
 * shape exists to prevent, both found by running it:
 *
 * <ol>
 *   <li><b>Proxy bypass.</b> {@code @Transactional} only applies across a bean
 *       boundary, so the scheduled worker must live in a different bean. A
 *       self-invoked transactional method silently gets no transaction: rows come
 *       back detached, {@code FOR UPDATE SKIP LOCKED} releases immediately, and
 *       every status update is discarded.</li>
 *   <li><b>Self-deadlock.</b> Holding {@code FOR UPDATE} on an outbox row while
 *       fanning out in a nested transaction deadlocks: notification_events has a
 *       foreign key to outbox_events, so the child insert waits on the parent's
 *       row lock, which the outer transaction will not release until the nested
 *       call it is waiting for returns.</li>
 * </ol>
 *
 * <p>So: claim and mark PROCESSING, <b>commit</b>, then process each event in its
 * own fresh transaction with no locks held from the claim.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxBatchProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationFanoutService fanoutService;

    @Value("${app.outbox.batch-size:20}")
    private int batchSize;

    @Value("${app.outbox.max-retry:5}")
    private int maxRetry;

    @Value("${app.outbox.base-backoff-ms:1000}")
    private long baseBackoffMs;

    @Value("${app.outbox.stuck-timeout-seconds:300}")
    private long stuckTimeoutSeconds;

    /**
     * Phase 1: claim due events and mark them PROCESSING. Commits immediately, so
     * no row lock survives into the processing phase.
     *
     * <p>Also reclaims rows left PROCESSING by a crashed instance once they exceed
     * the stuck timeout — the same recovery idea the recalc queue already uses.
     */
    @Transactional
    public List<Long> claimBatch() {
        OffsetDateTime now = OffsetDateTime.now();

        List<OutboxEvent> claimed = outboxEventRepository.claimBatch(
                now, now.minusSeconds(stuckTimeoutSeconds), batchSize);

        for (OutboxEvent event : claimed) {
            event.setStatus(OutboxEventStatus.PROCESSING);
        }

        return claimed.stream().map(OutboxEvent::getId).toList();
    }

    /**
     * Phase 2: one event, one transaction. Fan-out joins this transaction, so the
     * notification records and the PROCESSED flag commit together — an event is
     * never marked done unless every durable record it implies exists.
     */
    @Transactional
    public void processOne(Long eventId) {
        fanoutService.process(eventId);

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        event.setStatus(OutboxEventStatus.PROCESSED);
        event.setProcessedAt(OffsetDateTime.now());
        event.setLastError(null);
    }

    /**
     * Phase 3: record a failure in a separate transaction, because the one that
     * threw has already rolled back and cannot persist anything.
     *
     * <p>Exponential backoff, capped by max-retry. An exhausted event stays FAILED
     * with next_attempt_at far in the future: it stops being claimed but remains
     * visible for operational review rather than vanishing.
     */
    @Transactional
    public void recordFailure(Long eventId, Exception ex) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            int attempts = event.getAttemptCount() + 1;
            event.setAttemptCount(attempts);
            event.setStatus(OutboxEventStatus.FAILED);
            event.setLastError(ErrorSanitizer.sanitize(ex));

            if (attempts >= maxRetry) {
                event.setNextAttemptAt(OffsetDateTime.now().plusYears(100));
                log.error("[Outbox] Event {} permanently failed after {} attempts",
                        eventId, attempts);
            } else {
                long delayMs = baseBackoffMs * (1L << (attempts - 1));
                event.setNextAttemptAt(OffsetDateTime.now().plusNanos(delayMs * 1_000_000));
            }
        });
    }
}
