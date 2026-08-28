package com.aleksandarparipovic.marel_app.notification_delivery;

import com.aleksandarparipovic.marel_app.common.ErrorSanitizer;
import com.aleksandarparipovic.marel_app.notification_event.NotificationEvent;
import com.aleksandarparipovic.marel_app.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The transactional half of delivery processing.
 *
 * <p>A separate bean from {@link NotificationDeliveryWorker} for two reasons.
 * First, {@code @Transactional} only applies across a bean boundary — a scheduled
 * method calling it on {@code this} silently gets no transaction at all. Second,
 * the send itself must happen BETWEEN transactions: claim and mark PROCESSING
 * here, send outside, then record the outcome here again. An external provider
 * call must never hold a database transaction open.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryBatchProcessor {

    private final NotificationDeliveryRepository deliveryRepository;

    @Value("${app.notifications.delivery.batch-size:20}")
    private int batchSize;

    @Value("${app.notifications.delivery.max-retry:5}")
    private int maxRetry;

    @Value("${app.notifications.delivery.base-backoff-ms:5000}")
    private long baseBackoffMs;

    public int getMaxRetry() {
        return maxRetry;
    }

    /**
     * Claims due rows, flips them to PROCESSING, and returns detached data.
     *
     * <p>Returning plain records rather than entities is deliberate: the send runs
     * after this transaction commits, and touching a managed entity there would
     * either need a new transaction or throw a lazy-loading error.
     */
    @Transactional
    public List<PendingSend> claimBatch() {
        List<NotificationDelivery> claimed = deliveryRepository.claimBatch(
                OffsetDateTime.now(), maxRetry, batchSize);

        List<PendingSend> sends = new ArrayList<>(claimed.size());

        for (NotificationDelivery delivery : claimed) {
            delivery.setStatus(NotificationDeliveryStatus.PROCESSING);
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);

            NotificationEvent event = delivery.getNotificationEvent();
            // The actor is read HERE, inside the transaction: the association is
            // lazy and the send happens after this commits.
            User actor = event.getActorUser();

            sends.add(new PendingSend(
                    delivery.getId(),
                    delivery.getChannel(),
                    recipientsOf(delivery),
                    subjectOf(delivery, event),
                    event.getMessage(),
                    actor == null ? null : actor.getFullName(),
                    actor == null ? null : actor.getEmailAddress(),
                    event.getEntityType(),
                    event.getEntityId(),
                    delivery.getMessageId(),
                    delivery.getInReplyTo(),
                    delivery.getReferencesHeader()
            ));
        }

        return sends;
    }

    /**
     * One address or many, as one list — the rest of the pipeline should not have
     * to know which kind of row this was.
     */
    private static List<String> recipientsOf(NotificationDelivery delivery) {
        if (delivery.getRecipientEmails() != null && !delivery.getRecipientEmails().isBlank()) {
            return Arrays.stream(delivery.getRecipientEmails().split(","))
                    .map(String::trim)
                    .filter(address -> !address.isEmpty())
                    .toList();
        }
        return delivery.getRecipientEmail() == null
                ? List.of()
                : List.of(delivery.getRecipientEmail());
    }

    /**
     * A threaded mail carries the conversation's frozen subject, written onto the
     * row when it was queued. The event title is right for everything else, but
     * using it here would change the subject as the event type changes — and a
     * changed subject is the one thing that splits a conversation even when the
     * References chain is perfect.
     */
    private static String subjectOf(NotificationDelivery delivery, NotificationEvent event) {
        return delivery.getThreadSubject() != null && !delivery.getThreadSubject().isBlank()
                ? delivery.getThreadSubject()
                : event.getTitle();
    }

    @Transactional
    public void markSent(Long deliveryId) {
        deliveryRepository.findById(deliveryId).ifPresent(NotificationDelivery::markSent);
    }

    @Transactional
    public void markFailed(Long deliveryId, Exception ex) {
        deliveryRepository.findById(deliveryId).ifPresent(delivery -> {
            int attempts = delivery.getAttemptCount();
            String sanitized = ErrorSanitizer.sanitize(ex);

            if (attempts >= maxRetry) {
                // Stays FAILED and stops being claimed — the claim query filters on
                // attempt_count — but remains inspectable.
                delivery.markFailed(sanitized, OffsetDateTime.now());
                log.error("[Delivery] {} permanently failed after {} attempts",
                        deliveryId, attempts);
            } else {
                long delayMs = baseBackoffMs * (1L << (attempts - 1));
                delivery.markFailed(sanitized,
                        OffsetDateTime.now().plusNanos(delayMs * 1_000_000));
            }
        });
    }

    /** Immutable snapshot of what to send, safe to use outside a transaction. */
    public record PendingSend(
            Long deliveryId,
            NotificationChannel channel,
            List<String> recipientEmails,
            String subject,
            String body,
            String actorName,
            String actorEmail,
            String entityType,
            Long entityId,
            String messageId,
            String inReplyTo,
            String references
    ) {
    }
}
