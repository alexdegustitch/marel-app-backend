package com.aleksandarparipovic.marel_app.outbox;

import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.notification_delivery.NotificationChannel;
import com.aleksandarparipovic.marel_app.notification_delivery.NotificationDelivery;
import com.aleksandarparipovic.marel_app.notification_delivery.NotificationDeliveryRepository;
import com.aleksandarparipovic.marel_app.notification_event.NotificationEvent;
import com.aleksandarparipovic.marel_app.notification_event.NotificationEventRepository;
import com.aleksandarparipovic.marel_app.production_order_recipient.ProductionOrderRecipient;
import com.aleksandarparipovic.marel_app.production_order_recipient.ProductionOrderRecipientRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_notification.UserNotification;
import com.aleksandarparipovic.marel_app.user_notification.UserNotificationRepository;
import com.aleksandarparipovic.marel_app.user_preferences.UserPreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one outbox event into its durable notification records.
 *
 * <p>Runs inside the caller's per-event transaction (MANDATORY). It must NOT open
 * a nested REQUIRES_NEW transaction: notification_events has a foreign key to
 * outbox_events, so inserting the child while an outer transaction held
 * FOR UPDATE on the parent row deadlocked the two — the outer waiting for the
 * nested call, the nested waiting for the outer's row lock. The worker therefore
 * commits its claim first and calls this in a fresh, single transaction per event.
 *
 * <p><b>Idempotency</b> is structural, not hopeful: the notification event is
 * looked up by outbox id first (unique index), each user notification is checked
 * per (event, user) (unique index), and each delivery per (event, target) (unique
 * indexes). Replaying an event therefore produces nothing new.
 */
@Service
@RequiredArgsConstructor
public class NotificationFanoutService {

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationEventRepository notificationEventRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final ProductionOrderRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final UserPreferencesService userPreferencesService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void process(Long outboxEventId) {
        // Idempotency gate. uq_notification_events_outbox_event_id makes this the
        // authoritative "already done" check, so a retried or concurrently
        // reprocessed event produces nothing new.
        if (notificationEventRepository.findByOutboxEvent_Id(outboxEventId).isPresent()) {
            return;
        }

        OutboxEvent event = outboxEventRepository.findById(outboxEventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Outbox event not found: " + outboxEventId));

        NotificationEvent notificationEvent = createNotificationEvent(event);

        Set<User> inAppRecipients = resolveInAppRecipients(event);
        for (User recipient : inAppRecipients) {
            createUserNotification(notificationEvent, recipient);
            createInAppDelivery(notificationEvent, recipient);
        }

        if (event.getEventType() == OutboxEventType.PRODUCTION_ORDER_COMPLETED) {
            createEmailDeliveriesForOrder(notificationEvent, event.getAggregateId());
        }
    }

    private NotificationEvent createNotificationEvent(OutboxEvent event) {
        JsonNode payload = event.getPayload();

        return notificationEventRepository.save(NotificationEvent.builder()
                .outboxEvent(event)
                .type(event.getEventType())
                .entityType(event.getAggregateType().name())
                .entityId(event.getAggregateId())
                .title(titleFor(event))
                .message(messageFor(event, payload))
                .payload(payload)
                .build());
    }

    /**
     * Who must see this in the application.
     *
     * <p>Resolved from permission and business relationship, never a hard-coded
     * role name — so changing which role approves registrations automatically
     * changes who is told about them.
     */
    private Set<User> resolveInAppRecipients(OutboxEvent event) {
        Set<User> recipients = new LinkedHashSet<>();
        JsonNode payload = event.getPayload();

        switch (event.getEventType()) {
            case USER_REGISTRATION_REQUESTED ->
                    recipients.addAll(usersWith(AppPermission.USER_REGISTRATION_APPROVE));

            case USER_REGISTRATION_APPROVED, USER_REGISTRATION_DECLINED ->
                    addUser(recipients, longOf(payload, "userId"));

            case MANUFACTURING_TIME_REQUEST_CREATED ->
                    recipients.addAll(usersWith(AppPermission.MANUFACTURING_TIME_REQUEST_PROCESS));

            case MANUFACTURING_TIME_REQUEST_ASSIGNED ->
                    addUser(recipients, longOf(payload, "assignedToUserId"));

            case MANUFACTURING_TIME_REQUEST_COMPLETED, MANUFACTURING_TIME_REQUEST_DECLINED ->
                    addUser(recipients, longOf(payload, "createdByUserId"));

            case PRODUCTION_ORDER_COMPLETED ->
                    addUser(recipients, longOf(payload, "responsibleUserId"));
        }

        // Never notify a person who can no longer use the application.
        recipients.removeIf(u -> u.getAccountStatus()
                != com.aleksandarparipovic.marel_app.user.UserAccountStatus.ACTIVE);

        return recipients;
    }

    private List<User> usersWith(AppPermission permission) {
        List<String> roleNames = permissionService.roleNamesWith(permission);
        if (roleNames.isEmpty()) {
            return List.of();
        }
        return userRepository.findActiveByRoleNames(roleNames);
    }

    private void addUser(Set<User> recipients, Long userId) {
        if (userId != null) {
            userRepository.findById(userId).ifPresent(recipients::add);
        }
    }

    private void createUserNotification(NotificationEvent event, User user) {
        if (userNotificationRepository.existsByNotificationEvent_IdAndUser_Id(
                event.getId(), user.getId())) {
            return;
        }
        userNotificationRepository.save(UserNotification.builder()
                .notificationEvent(event)
                .user(user)
                .build());
    }

    private void createInAppDelivery(NotificationEvent event, User user) {
        if (!userPreferencesService.inAppNotificationsEnabled(user.getId())) {
            return;
        }
        if (deliveryRepository.existsByNotificationEvent_IdAndChannelAndRecipientUser_Id(
                event.getId(), NotificationChannel.IN_APP, user.getId())) {
            return;
        }
        deliveryRepository.save(NotificationDelivery.builder()
                .notificationEvent(event)
                .channel(NotificationChannel.IN_APP)
                .recipientUser(user)
                .build());
    }

    /**
     * Email goes to the order's RECIPIENT SNAPSHOT, never to current mailing-list
     * membership — that is the whole point of the snapshot.
     */
    private void createEmailDeliveriesForOrder(NotificationEvent event, Long productionOrderId) {
        for (ProductionOrderRecipient recipient :
                recipientRepository.findActiveByProductionOrderId(productionOrderId)) {

            User user = recipient.getUser();
            if (user != null && !userPreferencesService.emailNotificationsEnabled(user.getId())) {
                continue;
            }

            if (deliveryRepository.existsByNotificationEvent_IdAndChannelAndRecipientEmail(
                    event.getId(), NotificationChannel.EMAIL, recipient.getRecipientEmail())) {
                continue;
            }

            deliveryRepository.save(NotificationDelivery.builder()
                    .notificationEvent(event)
                    .channel(NotificationChannel.EMAIL)
                    .recipientUser(user)
                    .recipientEmail(recipient.getRecipientEmail())
                    .build());
        }
    }

    private static Long longOf(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field)) {
            return null;
        }
        return payload.get(field).asLong();
    }

    private static String textOf(JsonNode payload, String field, String fallback) {
        if (payload == null || !payload.hasNonNull(field)) {
            return fallback;
        }
        return payload.get(field).asText(fallback);
    }

    private static String titleFor(OutboxEvent event) {
        return switch (event.getEventType()) {
            case USER_REGISTRATION_REQUESTED -> "Nova registracija čeka odobrenje";
            case USER_REGISTRATION_APPROVED -> "Nalog je odobren";
            case USER_REGISTRATION_DECLINED -> "Registracija je odbijena";
            case MANUFACTURING_TIME_REQUEST_CREATED -> "Novi zahtev za vreme izrade";
            case MANUFACTURING_TIME_REQUEST_ASSIGNED -> "Zahtev vam je dodeljen";
            case MANUFACTURING_TIME_REQUEST_COMPLETED -> "Zahtev je završen";
            case MANUFACTURING_TIME_REQUEST_DECLINED -> "Zahtev je odbijen";
            case PRODUCTION_ORDER_COMPLETED -> "Nalog za proizvodnju je isporučen";
        };
    }

    /**
     * Message text stays generic and names only what the recipient is already
     * entitled to see. Following the entity link still goes through the normal
     * authorization check on that entity.
     */
    private static String messageFor(OutboxEvent event, JsonNode payload) {
        return switch (event.getEventType()) {
            case USER_REGISTRATION_REQUESTED -> "Korisnik "
                    + textOf(payload, "fullName", "nepoznat") + " čeka odobrenje naloga.";
            case USER_REGISTRATION_APPROVED -> "Vaš nalog je odobren i sada je aktivan.";
            case USER_REGISTRATION_DECLINED -> "Vaša registracija je odbijena.";
            case MANUFACTURING_TIME_REQUEST_CREATED -> "Novi zahtev za proizvod "
                    + textOf(payload, "productName", "-") + ".";
            case MANUFACTURING_TIME_REQUEST_ASSIGNED -> "Zahtev za proizvod "
                    + textOf(payload, "productName", "-") + " je dodeljen vama.";
            case MANUFACTURING_TIME_REQUEST_COMPLETED -> "Zahtev za proizvod "
                    + textOf(payload, "productName", "-") + " je završen.";
            case MANUFACTURING_TIME_REQUEST_DECLINED -> "Zahtev za proizvod "
                    + textOf(payload, "productName", "-") + " je odbijen.";
            case PRODUCTION_ORDER_COMPLETED -> "Nalog "
                    + textOf(payload, "orderCode", "-") + " je isporučen.";
        };
    }
}
