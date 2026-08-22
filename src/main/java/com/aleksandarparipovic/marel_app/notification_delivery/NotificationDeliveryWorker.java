package com.aleksandarparipovic.marel_app.notification_delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Second delivery stage: actually sends what the fan-out queued.
 *
 * <p>Split from the outbox worker because sending talks to an external provider.
 * The claim commits first, the provider call happens here with no transaction
 * held, and the outcome is recorded in a third short transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryWorker {

    private final DeliveryBatchProcessor processor;
    private final EmailSender emailSender;
    private final NotificationEmailComposer composer;

    @Scheduled(fixedDelayString = "${app.notifications.delivery.poll-interval-ms:5000}")
    public void drain() {
        try {
            for (DeliveryBatchProcessor.PendingSend send : processor.claimBatch()) {
                dispatch(send);
            }
        } catch (Exception ex) {
            log.error("[NotificationDeliveryWorker] Batch processing failed", ex);
        }
    }

    private void dispatch(DeliveryBatchProcessor.PendingSend send) {
        try {
            if (send.channel() == NotificationChannel.EMAIL) {
                // No transaction is open here — see DeliveryBatchProcessor.
                emailSender.send(composer.compose(send));
            }
            // IN_APP needs no transport: the user_notifications row IS the delivery.
            processor.markSent(send.deliveryId());
        } catch (Exception ex) {
            processor.markFailed(send.deliveryId(), ex);
        }
    }
}
