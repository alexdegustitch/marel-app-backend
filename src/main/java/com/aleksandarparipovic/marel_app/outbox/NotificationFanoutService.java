package com.aleksandarparipovic.marel_app.outbox;

import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.notification.UserNotificationPushService;
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

import java.util.ArrayList;
import java.util.EnumSet;
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
    private final UserNotificationPushService pushService;

    /**
     * Order events that go out by e-mail as well as in-app. Everything not listed
     * in one of these two sets is in-app only — an event has to earn a place in
     * somebody's inbox.
     */
    private static final Set<OutboxEventType> ORDER_EMAIL_EVENT_TYPES = EnumSet.of(
            OutboxEventType.PRODUCTION_ORDER_COMPLETED,
            OutboxEventType.PRODUCTION_ORDER_DEADLINE_CHANGED);

    /**
     * The two decisions about a person's own account. These MUST go by e-mail:
     * an approved user has not signed in yet and a declined one never will, so
     * the in-app notification is a message neither of them can reach.
     */
    private static final Set<OutboxEventType> ACCOUNT_DECISION_EMAIL_EVENT_TYPES = EnumSet.of(
            OutboxEventType.USER_REGISTRATION_APPROVED,
            OutboxEventType.USER_REGISTRATION_DECLINED);

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
            boolean isNew = createUserNotification(notificationEvent, recipient);
            createInAppDelivery(notificationEvent, recipient);

            // Only a genuinely new row is announced. A replayed outbox event
            // creates nothing, and must therefore push nothing — otherwise a
            // retry would pop a toast for something the reader saw yesterday.
            if (isNew) {
                pushService.signal(recipient.getUsername());
            }
        }

        if (ORDER_EMAIL_EVENT_TYPES.contains(event.getEventType())) {
            createEmailDeliveriesForOrder(notificationEvent, event.getAggregateId());
            // The person who caused it gets their own copy even when they are not
            // on the list: the application sent the mail, so it is nowhere in their
            // Sent folder, and a copy in the inbox is the only way they can find it
            // again later.
            createEmailDeliveryForActor(notificationEvent);
        }

        if (ACCOUNT_DECISION_EMAIL_EVENT_TYPES.contains(event.getEventType())) {
            createEmailDeliveryForApplicant(notificationEvent, event.getPayload());
        }
    }

    private NotificationEvent createNotificationEvent(OutboxEvent event) {
        JsonNode payload = event.getPayload();

        // Whose action this was. Carried in the payload by the publisher rather
        // than read from the security context here: fan-out runs on a worker
        // thread, long after the request that caused the event is gone.
        User actor = null;
        Long actorUserId = longOf(payload, "actorUserId");
        if (actorUserId != null) {
            actor = userRepository.findById(actorUserId).orElse(null);
        }

        return notificationEventRepository.save(NotificationEvent.builder()
                .outboxEvent(event)
                .actorUser(actor)
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

            case PRODUCTION_ORDER_DEADLINE_CHANGED ->
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

    /** @return true when this call created the row, false when it already existed. */
    private boolean createUserNotification(NotificationEvent event, User user) {
        if (userNotificationRepository.existsByNotificationEvent_IdAndUser_Id(
                event.getId(), user.getId())) {
            return false;
        }
        userNotificationRepository.save(UserNotification.builder()
                .notificationEvent(event)
                .user(user)
                .build());
        return true;
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

    /**
     * The applicant's copy of the decision on their own registration.
     *
     * <p>Deliberately NOT gated on the e-mail notification preference, unlike
     * every other mail this service queues. This one is not news about somebody
     * else's work that a person may opt out of — it is the answer to their own
     * request, and it is the only channel left: the account is either not yet
     * usable or refused outright, so nobody can sign in to read it in the app.
     *
     * <p>The address comes from the event payload, written when the decision was
     * made. Fan-out runs later on a worker thread, so it is read here from the
     * user record only as a fallback for events published before that field
     * existed.
     */
    private void createEmailDeliveryForApplicant(NotificationEvent event, JsonNode payload) {
        Long applicantId = longOf(payload, "userId");
        if (applicantId == null) {
            return;
        }

        User applicant = userRepository.findById(applicantId).orElse(null);
        String email = textOf(payload, "emailAddress", null);
        if ((email == null || email.isBlank()) && applicant != null) {
            email = applicant.getEmailAddress();
        }
        if (email == null || email.isBlank()) {
            return;
        }

        if (deliveryRepository.existsByNotificationEvent_IdAndChannelAndRecipientEmail(
                event.getId(), NotificationChannel.EMAIL, email)) {
            return;
        }

        deliveryRepository.save(NotificationDelivery.builder()
                .notificationEvent(event)
                .channel(NotificationChannel.EMAIL)
                .recipientUser(applicant)
                .recipientEmail(email)
                .build());
    }

    /**
     * A copy for the actor, skipped when the snapshot already covers them so the
     * unique index on (event, e-mail) is never the thing that catches it.
     */
    private void createEmailDeliveryForActor(NotificationEvent event) {
        User actor = event.getActorUser();
        if (actor == null || actor.getEmailAddress() == null || actor.getEmailAddress().isBlank()) {
            return;
        }
        if (!userPreferencesService.emailNotificationsEnabled(actor.getId())) {
            return;
        }
        if (deliveryRepository.existsByNotificationEvent_IdAndChannelAndRecipientEmail(
                event.getId(), NotificationChannel.EMAIL, actor.getEmailAddress())) {
            return;
        }

        deliveryRepository.save(NotificationDelivery.builder()
                .notificationEvent(event)
                .channel(NotificationChannel.EMAIL)
                .recipientUser(actor)
                .recipientEmail(actor.getEmailAddress())
                .build());
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

    /** A payload array of date descriptions, as one readable phrase. */
    private static String joinOf(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field) || !payload.get(field).isArray()
                || payload.get(field).isEmpty()) {
            return "nije bio postavljen";
        }
        List<String> values = new ArrayList<>();
        payload.get(field).forEach(node -> values.add(node.asText()));
        return String.join(", ", values);
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
            case PRODUCTION_ORDER_DEADLINE_CHANGED -> "Promenjen rok isporuke";
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
            case USER_REGISTRATION_APPROVED ->
                    "Vaš nalog je odobren i sada je aktivan. Možete da se prijavite.";
            // The reason is the whole message for the person who was turned away,
            // so it is carried through rather than summarised.
            case USER_REGISTRATION_DECLINED -> "Vaša registracija je odbijena. Obrazloženje: "
                    + textOf(payload, "reviewNote", "nije navedeno");
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
            case PRODUCTION_ORDER_DEADLINE_CHANGED -> "Nalog "
                    + textOf(payload, "orderCode", "-") + ": rok "
                    + joinOf(payload, "deadlinesBefore") + " promenjen na "
                    + joinOf(payload, "deadlinesAfter") + ".";
        };
    }
}
