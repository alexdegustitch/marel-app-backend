package com.aleksandarparipovic.marel_app.notification_delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    /**
     * Claims due deliveries. FOR UPDATE SKIP LOCKED so several instances can drain
     * the table concurrently without ever handing the same row to two workers.
     */
    @Query(value = """
            SELECT * FROM notification_deliveries
            WHERE status IN ('PENDING', 'FAILED')
              AND next_attempt_at <= :now
              AND attempt_count < :maxAttempts
            ORDER BY next_attempt_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationDelivery> claimBatch(
            @Param("now") OffsetDateTime now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize
    );

    boolean existsByNotificationEvent_IdAndChannelAndRecipientEmail(
            Long notificationEventId, NotificationChannel channel, String recipientEmail);

    /**
     * Is there already a GROUP send for this event? recipient_email is NULL on
     * those rows, so the (event, address) uniqueness index cannot answer it.
     */
    boolean existsByNotificationEvent_IdAndChannelAndRecipientEmailsIsNotNull(
            Long notificationEventId, NotificationChannel channel);

    boolean existsByNotificationEvent_IdAndChannelAndRecipientUser_Id(
            Long notificationEventId, NotificationChannel channel, Long recipientUserId);

    long countByStatus(NotificationDeliveryStatus status);
}
