package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.notification_event.NotificationEventRepository;
import com.aleksandarparipovic.marel_app.outbox.*;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_notification.UserNotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox processing must be safe to replay.
 *
 * <p>This matters because retries and crash recovery genuinely do reprocess
 * events: a worker that dies mid-batch leaves rows in PROCESSING which are later
 * reclaimed. If fan-out were not idempotent, users would get duplicate
 * notifications and recipients duplicate emails.
 */
@Transactional
class OutboxIdempotencyIT extends AbstractIntegrationTest {

    @Autowired private OutboxEventPublisher publisher;
    @Autowired private OutboxBatchProcessor processor;
    @Autowired private NotificationEventRepository notificationEventRepository;
    @Autowired private UserNotificationRepository userNotificationRepository;
    @Autowired private UserRepository userRepository;

    private User anActiveUser() {
        return userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == UserAccountStatus.ACTIVE)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("processing the same event twice creates exactly one notification event")
    void replayCreatesNoDuplicateEvent() {
        User user = anActiveUser();
        OutboxEvent event = publisher.publish(
                OutboxEventType.USER_REGISTRATION_APPROVED,
                OutboxAggregateType.USER_REGISTRATION_REQUEST,
                1L,
                Map.of("userId", user.getId(), "fullName", "Test User"));

        processor.processOne(event.getId());
        long afterFirst = notificationEventRepository.count();

        // Simulate a retry / crash-recovery reprocess of the very same row.
        processor.processOne(event.getId());

        assertThat(notificationEventRepository.count()).isEqualTo(afterFirst);
        assertThat(notificationEventRepository.findByOutboxEvent_Id(event.getId())).isPresent();
    }

    @Test
    @DisplayName("replay does not deliver a second notification to the same user")
    void replayCreatesNoDuplicateUserNotification() {
        User user = anActiveUser();
        OutboxEvent event = publisher.publish(
                OutboxEventType.USER_REGISTRATION_APPROVED,
                OutboxAggregateType.USER_REGISTRATION_REQUEST,
                2L,
                Map.of("userId", user.getId(), "fullName", "Test User"));

        processor.processOne(event.getId());
        long afterFirst = userNotificationRepository.count();

        processor.processOne(event.getId());

        assertThat(userNotificationRepository.count()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a processed event is marked PROCESSED with a timestamp")
    void processingMarksTheEventDone() {
        User user = anActiveUser();
        OutboxEvent event = publisher.publish(
                OutboxEventType.USER_REGISTRATION_APPROVED,
                OutboxAggregateType.USER_REGISTRATION_REQUEST,
                3L,
                Map.of("userId", user.getId()));

        processor.processOne(event.getId());

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("recipients are resolved from permission, so approvers get registration events")
    void recipientsResolvedFromPermission() {
        User applicant = anActiveUser();
        OutboxEvent event = publisher.publish(
                OutboxEventType.USER_REGISTRATION_REQUESTED,
                OutboxAggregateType.USER_REGISTRATION_REQUEST,
                4L,
                Map.of("userId", applicant.getId(), "fullName", "Novi Korisnik"));

        processor.processOne(event.getId());

        var notificationEvent =
                notificationEventRepository.findByOutboxEvent_Id(event.getId()).orElseThrow();

        // Everyone holding USER_REGISTRATION_APPROVE — resolved by capability, not
        // by a hard-coded role name at the call site.
        assertThat(userNotificationRepository
                .existsByNotificationEvent_IdAndUser_Id(notificationEvent.getId(), applicant.getId()))
                .isTrue();
        assertThat(notificationEvent.getTitle()).contains("registracija");
    }
}
