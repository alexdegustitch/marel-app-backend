package com.aleksandarparipovic.marel_app.outbox;

import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.notification.UserNotificationPushService;
import com.aleksandarparipovic.marel_app.notification_delivery.NotificationChannel;
import com.aleksandarparipovic.marel_app.notification_delivery.NotificationDelivery;
import com.aleksandarparipovic.marel_app.notification_delivery.NotificationDeliveryRepository;
import com.aleksandarparipovic.marel_app.notification_event.NotificationEvent;
import com.aleksandarparipovic.marel_app.notification_event.NotificationEventRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_email_thread.ProductionOrderEmailThreadService;
import com.aleksandarparipovic.marel_app.production_order_recipient.ProductionOrderRecipient;
import com.aleksandarparipovic.marel_app.production_order_recipient.ProductionOrderRecipientRepository;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
import com.aleksandarparipovic.marel_app.sample_order_email_thread.SampleOrderEmailThreadService;
import com.aleksandarparipovic.marel_app.sample_order_recipient.SampleOrderRecipient;
import com.aleksandarparipovic.marel_app.sample_order_recipient.SampleOrderRecipientRepository;
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
import java.util.Locale;
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
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderEmailThreadService emailThreadService;
    private final SampleOrderRecipientRepository sampleRecipientRepository;
    private final SampleOrderRepository sampleOrderRepository;
    private final SampleOrderEmailThreadService sampleEmailThreadService;
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
            OutboxEventType.PRODUCTION_ORDER_CREATED,
            OutboxEventType.PRODUCTION_ORDER_UPDATED,
            OutboxEventType.PRODUCTION_ORDER_COMPLETED,
            OutboxEventType.PRODUCTION_ORDER_DEADLINE_CHANGED);

    /**
     * The same, for a nalog za izradu uzoraka. A separate set rather than three
     * more entries above, because the two are read by two different methods:
     * which recipient table the addresses come from, and which thread the mail
     * joins, is decided by exactly this distinction.
     */
    private static final Set<OutboxEventType> SAMPLE_ORDER_EMAIL_EVENT_TYPES = EnumSet.of(
            OutboxEventType.SAMPLE_ORDER_CREATED,
            OutboxEventType.SAMPLE_ORDER_UPDATED,
            OutboxEventType.SAMPLE_ORDER_COMPLETED);

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
            createOrderConversationDelivery(notificationEvent, event.getAggregateId());
        }

        if (SAMPLE_ORDER_EMAIL_EVENT_TYPES.contains(event.getEventType())) {
            createSampleOrderConversationDelivery(notificationEvent, event.getAggregateId());
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

            // The order-scope workflow reaches the same people for the same
            // reasons: whoever can answer it hears that it was raised, the new
            // owner hears that it is theirs, and the requester hears the outcome.
            case ORDER_SCOPE_REQUEST_CREATED ->
                    recipients.addAll(usersWith(AppPermission.ORDER_SCOPE_REQUEST_PROCESS));

            case ORDER_SCOPE_REQUEST_ASSIGNED ->
                    addUser(recipients, longOf(payload, "assignedToUserId"));

            case ORDER_SCOPE_REQUEST_COMPLETED, ORDER_SCOPE_REQUEST_DECLINED ->
                    addUser(recipients, longOf(payload, "createdByUserId"));

            // Deliberately no in-app recipient: the mail is what opens the
            // conversation, and the only person present at creation is the
            // one who just did it.
            case PRODUCTION_ORDER_CREATED -> { }

            case PRODUCTION_ORDER_UPDATED, PRODUCTION_ORDER_COMPLETED ->
                    addUser(recipients, longOf(payload, "responsibleUserId"));

            case PRODUCTION_ORDER_DEADLINE_CHANGED ->
                    addUser(recipients, longOf(payload, "responsibleUserId"));

            // Same reasoning as the production order above: nobody is told
            // in-app that an order was created, because the only person present
            // is the one who just created it. The mail is what opens the
            // conversation.
            case SAMPLE_ORDER_CREATED -> { }

            case SAMPLE_ORDER_UPDATED, SAMPLE_ORDER_COMPLETED ->
                    addUser(recipients, longOf(payload, "responsibleUserId"));

            /*
             * The payroll change request, on the same shape as the other two
             * request workflows: whoever can answer it hears that it was raised,
             * and whoever raised it hears the outcome.
             */
            case PAYROLL_CHANGE_REQUEST_CREATED ->
                    recipients.addAll(usersWith(AppPermission.PAYROLL_CHANGE_REQUEST_PROCESS));

            case PAYROLL_CHANGE_REQUEST_ACCEPTED, PAYROLL_CHANGE_REQUEST_DECLINED ->
                    addUser(recipients, longOf(payload, "requestedByUserId"));
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
     * ONE mail about this order, addressed to everybody at once.
     *
     * <p>Addresses come from the order's RECIPIENT SNAPSHOT, never from current
     * mailing-list membership — that is the whole point of the snapshot (§5.1.11).
     *
     * <p>One message rather than one per person, because these colleagues are
     * having a conversation. A single message gives them all the same Message-ID,
     * so when one of them hits Reply All the answer lands in everybody's thread.
     * Separate copies would give each recipient a private chain, and the first
     * reply would split the discussion along exactly that seam.
     *
     * <p>The actor is one of the addresses rather than getting a separate mail.
     * The reason they need a copy is unchanged — the application sent it, so it is
     * nowhere in their Sent folder — but a separate mail would carry its own
     * Message-ID and sit outside the conversation it is about.
     */
    private void createOrderConversationDelivery(NotificationEvent event, Long productionOrderId) {
        // uq_notification_deliveries_group is the real guarantee; this only spares
        // the thread a Message-ID that would be rolled back anyway.
        if (deliveryRepository.existsByNotificationEvent_IdAndChannelAndRecipientEmailsIsNotNull(
                event.getId(), NotificationChannel.EMAIL)) {
            return;
        }

        // LinkedHashSet: de-duplicated, but in snapshot order, so the To line
        // reads the way the list was built rather than in hash order.
        Set<String> addresses = new LinkedHashSet<>();

        for (ProductionOrderRecipient recipient :
                recipientRepository.findActiveByProductionOrderId(productionOrderId)) {

            User user = recipient.getUser();
            if (user != null && !userPreferencesService.emailNotificationsEnabled(user.getId())) {
                continue;
            }
            addAddress(addresses, recipient.getRecipientEmail());
        }

        User actor = event.getActorUser();
        if (actor != null && userPreferencesService.emailNotificationsEnabled(actor.getId())) {
            addAddress(addresses, actor.getEmailAddress());
        }

        if (addresses.isEmpty()) {
            // No snapshot yet, or everyone has e-mail switched off. Queueing a
            // message with an empty To would fail at the relay on every retry —
            // and, worse, would consume a Message-ID and leave the conversation
            // pointing at a mail nobody received.
            return;
        }

        ProductionOrder order = productionOrderRepository.findById(productionOrderId)
                .orElse(null);
        if (order == null) {
            return;
        }

        // Opens the conversation on the first mail and continues it afterwards.
        // MANDATORY inside this transaction: the id it hands out and the row that
        // carries it must commit together.
        ProductionOrderEmailThreadService.ThreadHeaders headers =
                emailThreadService.nextMessage(order);

        deliveryRepository.save(NotificationDelivery.builder()
                .notificationEvent(event)
                .channel(NotificationChannel.EMAIL)
                // No single recipient user: this row is addressed to several
                // people, some of whom may have no account at all.
                .recipientEmails(String.join(",", addresses))
                .messageId(headers.messageId())
                .inReplyTo(headers.inReplyTo())
                .referencesHeader(headers.references())
                .threadSubject(headers.subject())
                .build());
    }

    /**
     * ONE mail about this sample order, addressed to everybody at once.
     *
     * <p>The production-order method above with two things swapped: which
     * snapshot the addresses come from, and which conversation the message
     * joins. Every reason behind it is unchanged and is written out there — the
     * snapshot rather than live mailing-list membership, one message rather than
     * one per person, the actor in the To line rather than sent a private copy,
     * and nothing queued at all when there is nobody to tell.
     *
     * <p>Kept as its own method rather than parameterised over the two: the only
     * shared part is the address loop, and threading a pair of repositories and a
     * thread service through a generic version would hide which table a given
     * mail was actually built from.
     */
    private void createSampleOrderConversationDelivery(NotificationEvent event, Long sampleOrderId) {
        // uq_notification_deliveries_group is the real guarantee; this only spares
        // the thread a Message-ID that would be rolled back anyway.
        if (deliveryRepository.existsByNotificationEvent_IdAndChannelAndRecipientEmailsIsNotNull(
                event.getId(), NotificationChannel.EMAIL)) {
            return;
        }

        // LinkedHashSet: de-duplicated, but in snapshot order, so the To line
        // reads the way the list was built rather than in hash order.
        Set<String> addresses = new LinkedHashSet<>();

        for (SampleOrderRecipient recipient :
                sampleRecipientRepository.findActiveBySampleOrderId(sampleOrderId)) {

            User user = recipient.getUser();
            if (user != null && !userPreferencesService.emailNotificationsEnabled(user.getId())) {
                continue;
            }
            addAddress(addresses, recipient.getRecipientEmail());
        }

        User actor = event.getActorUser();
        if (actor != null && userPreferencesService.emailNotificationsEnabled(actor.getId())) {
            addAddress(addresses, actor.getEmailAddress());
        }

        if (addresses.isEmpty()) {
            // No snapshot yet, or everyone has e-mail switched off. Queueing a
            // message with an empty To would fail at the relay on every retry —
            // and, worse, would consume a Message-ID and leave the conversation
            // pointing at a mail nobody received.
            return;
        }

        SampleOrder order = sampleOrderRepository.findById(sampleOrderId).orElse(null);
        if (order == null) {
            return;
        }

        SampleOrderEmailThreadService.ThreadHeaders headers =
                sampleEmailThreadService.nextMessage(order);

        deliveryRepository.save(NotificationDelivery.builder()
                .notificationEvent(event)
                .channel(NotificationChannel.EMAIL)
                // No single recipient user: this row is addressed to several
                // people, some of whom may have no account at all.
                .recipientEmails(String.join(",", addresses))
                .messageId(headers.messageId())
                .inReplyTo(headers.inReplyTo())
                .referencesHeader(headers.references())
                .threadSubject(headers.subject())
                .build());
    }

    /** Normalised the same way the recipient snapshot stores addresses. */
    private static void addAddress(Set<String> addresses, String email) {
        if (email != null && !email.isBlank()) {
            addresses.add(email.trim().toLowerCase(Locale.ROOT));
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

    /** The changes this save made, as one sentence. */
    private static String joinChanges(JsonNode payload) {
        if (payload == null || !payload.hasNonNull("changes")
                || !payload.get("changes").isArray() || payload.get("changes").isEmpty()) {
            // publishOrderUpdated does not publish an empty list, so this is
            // only reachable for a replayed event written before the field
            // existed. Say something true rather than an empty sentence.
            return "izmenjen";
        }
        List<String> values = new ArrayList<>();
        payload.get("changes").forEach(node -> values.add(node.asText()));
        return String.join("; ", values);
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
            case ORDER_SCOPE_REQUEST_CREATED -> "Novi zahtev za razradu naloga";
            case ORDER_SCOPE_REQUEST_ASSIGNED -> "Zahtev za razradu vam je dodeljen";
            case ORDER_SCOPE_REQUEST_COMPLETED -> "Razrada naloga je predata";
            case ORDER_SCOPE_REQUEST_DECLINED -> "Zahtev za razradu je odbijen";
            case PRODUCTION_ORDER_CREATED -> "Otvoren nalog za proizvodnju";
            case PRODUCTION_ORDER_UPDATED -> "Izmenjen nalog za proizvodnju";
            case PRODUCTION_ORDER_COMPLETED -> "Nalog za proizvodnju je isporučen";
            case PRODUCTION_ORDER_DEADLINE_CHANGED -> "Promenjen rok isporuke";
            case SAMPLE_ORDER_CREATED -> "Otvoren nalog za izradu uzoraka";
            case SAMPLE_ORDER_UPDATED -> "Izmenjen nalog za izradu uzoraka";
            case SAMPLE_ORDER_COMPLETED -> "Nalog za izradu uzoraka je zatvoren";
            case PAYROLL_CHANGE_REQUEST_CREATED -> "Zahtev za izmenu obračuna";
            case PAYROLL_CHANGE_REQUEST_ACCEPTED -> "Obračun je vraćen na doradu";
            case PAYROLL_CHANGE_REQUEST_DECLINED -> "Zahtev za izmenu je odbijen";
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
            // The order is what identifies these, the way the product identifies
            // the ones above. Whether the whole order or one line was asked about
            // is left to the request itself: the message says what happened and
            // where, never more than the recipient may already see.
            case ORDER_SCOPE_REQUEST_CREATED -> "Nalog "
                    + textOf(payload, "productionOrderCode", "-")
                    + " čeka razradu operacija.";
            case ORDER_SCOPE_REQUEST_ASSIGNED -> "Razrada naloga "
                    + textOf(payload, "productionOrderCode", "-") + " je dodeljena vama.";
            case ORDER_SCOPE_REQUEST_COMPLETED -> "Razrada naloga "
                    + textOf(payload, "productionOrderCode", "-") + " je predata.";
            case ORDER_SCOPE_REQUEST_DECLINED -> "Zahtev za razradu naloga "
                    + textOf(payload, "productionOrderCode", "-") + " je odbijen.";
            // The first message of the conversation. It says what the order is,
            // because everything that follows arrives as a reply to it.
            case PRODUCTION_ORDER_CREATED -> "Otvoren je nalog "
                    + textOf(payload, "orderCode", "-") + " ("
                    + textOf(payload, "orderName", "-")
                    + "). Sve izmene stižu kao odgovor na ovu poruku.";
            // The list is the message. Naming the fields that moved is the
            // difference between a mail somebody reads and one they archive.
            case PRODUCTION_ORDER_UPDATED -> "Nalog "
                    + textOf(payload, "orderCode", "-") + ": "
                    + joinChanges(payload) + ".";
            case PRODUCTION_ORDER_COMPLETED -> "Nalog "
                    + textOf(payload, "orderCode", "-") + " je isporučen.";
            case PRODUCTION_ORDER_DEADLINE_CHANGED -> "Nalog "
                    + textOf(payload, "orderCode", "-") + ": rok "
                    + joinOf(payload, "deadlinesBefore") + " promenjen na "
                    + joinOf(payload, "deadlinesAfter") + ".";
            /*
             * The sample order's three, worded the same way and saying "uzorke"
             * where the production order says nothing: the two arrive in the same
             * inbox, and the first line has to tell them apart.
             */
            case SAMPLE_ORDER_CREATED -> "Otvoren je nalog za izradu uzoraka "
                    + textOf(payload, "orderCode", "-") + " ("
                    + textOf(payload, "orderName", "-")
                    + "). Sve izmene stižu kao odgovor na ovu poruku.";
            case SAMPLE_ORDER_UPDATED -> "Nalog za uzorke "
                    + textOf(payload, "orderCode", "-") + ": "
                    + joinChanges(payload) + ".";
            case SAMPLE_ORDER_COMPLETED -> "Nalog za uzorke "
                    + textOf(payload, "orderCode", "-") + " je zatvoren.";
            /*
             * The employee and the month, and nothing about the amounts — this
             * reaches whoever may ANSWER the request, and what they may see of
             * the payroll itself is decided on the payroll screen, not here.
             */
            case PAYROLL_CHANGE_REQUEST_CREATED -> "Traži se izmena obračuna za "
                    + textOf(payload, "employeeName", "-") + " ("
                    + textOf(payload, "period", "-") + ").";
            case PAYROLL_CHANGE_REQUEST_ACCEPTED -> "Obračun za "
                    + textOf(payload, "employeeName", "-") + " ("
                    + textOf(payload, "period", "-") + ") je vraćen na doradu.";
            case PAYROLL_CHANGE_REQUEST_DECLINED -> "Zahtev za izmenu obračuna za "
                    + textOf(payload, "employeeName", "-") + " ("
                    + textOf(payload, "period", "-") + ") je odbijen.";
        };
    }
}
