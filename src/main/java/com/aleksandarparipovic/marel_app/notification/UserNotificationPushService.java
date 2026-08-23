package com.aleksandarparipovic.marel_app.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Tells one user, and only that user, that something landed in their notification
 * centre.
 *
 * <p>What travels is a SIGNAL, never the notification: the client answers it by
 * re-reading {@code /api/notifications}, which is the endpoint that already
 * scopes and authorizes. So the socket carries no titles, no names and no entity
 * ids — a push that leaked would leak the fact that something happened, and
 * nothing about what.
 *
 * <p>Sent after commit. Sending inside the transaction would have the client
 * re-read before the row it is being told about is visible, and find nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationPushService {

    /** Client subscribes to "/user/queue/notifications". */
    private static final String DESTINATION = "/queue/notifications";

    private static final Map<String, String> SIGNAL = Map.of("event", "NOTIFICATION_CREATED");

    private final SimpMessagingTemplate messagingTemplate;

    public void signal(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        Runnable send = () -> {
            try {
                messagingTemplate.convertAndSendToUser(username, DESTINATION, SIGNAL);
            } catch (RuntimeException e) {
                // A push that fails is a slower notification, never a lost one:
                // the badge still polls, and the row is already committed.
                log.warn("Notification push failed for user={}", username, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
            return;
        }

        send.run();
    }
}
