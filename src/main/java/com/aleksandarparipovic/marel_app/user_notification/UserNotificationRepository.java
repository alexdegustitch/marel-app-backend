package com.aleksandarparipovic.marel_app.user_notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    boolean existsByNotificationEvent_IdAndUser_Id(Long notificationEventId, Long userId);

    @Query("""
            select n from UserNotification n
            join fetch n.notificationEvent
            where n.user.id = :userId
              and (:includeDismissed = true or n.dismissedAt is null)
            """)
    Page<UserNotification> findForUser(
            @Param("userId") Long userId,
            @Param("includeDismissed") boolean includeDismissed,
            Pageable pageable
    );

    @Query("""
            select n from UserNotification n
            join fetch n.notificationEvent
            where n.id = :id
            """)
    Optional<UserNotification> findDetailById(@Param("id") Long id);

    /** Backed by the partial index idx_user_notifications_unread. */
    long countByUser_IdAndReadAtIsNullAndDismissedAtIsNull(Long userId);

    /**
     * Bulk "mark all read" as one statement — loading every row to touch a
     * timestamp would be pointless traffic.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserNotification n
            set n.readAt = :readAt
            where n.user.id = :userId and n.readAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") OffsetDateTime readAt);
}
