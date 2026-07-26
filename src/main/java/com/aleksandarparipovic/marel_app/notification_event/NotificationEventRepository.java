package com.aleksandarparipovic.marel_app.notification_event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {

    /** The idempotency lookup: has this outbox row already produced its event? */
    Optional<NotificationEvent> findByOutboxEvent_Id(Long outboxEventId);
}
