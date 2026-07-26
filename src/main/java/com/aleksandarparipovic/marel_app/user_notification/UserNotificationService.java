package com.aleksandarparipovic.marel_app.user_notification;

import com.aleksandarparipovic.marel_app.notification_event.NotificationEvent;
import com.aleksandarparipovic.marel_app.user_notification.dto.UserNotificationResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * A user's notification centre.
 *
 * <p>Every mutating operation loads the row and checks it belongs to the caller,
 * so an id from someone else's notification is useless.
 */
@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private final UserNotificationRepository repository;

    @Transactional(readOnly = true)
    public Page<UserNotificationResponse> list(
            Long userId, boolean includeDismissed, Pageable pageable
    ) {
        return repository.findForUser(userId, includeDismissed, pageable).map(this::toResponse);
    }

    /** Backed by a partial index, so this stays cheap no matter how long the history. */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUser_IdAndReadAtIsNullAndDismissedAtIsNull(userId);
    }

    @Transactional
    public UserNotificationResponse markRead(Long notificationId, Long userId) {
        UserNotification notification = loadOwned(notificationId, userId);
        notification.markRead();
        return toResponse(notification);
    }

    @Transactional
    public UserNotificationResponse markUnread(Long notificationId, Long userId) {
        UserNotification notification = loadOwned(notificationId, userId);
        notification.markUnread();
        return toResponse(notification);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId, OffsetDateTime.now());
    }

    @Transactional
    public UserNotificationResponse dismiss(Long notificationId, Long userId) {
        UserNotification notification = loadOwned(notificationId, userId);
        notification.dismiss();
        return toResponse(notification);
    }

    @Transactional
    public UserNotificationResponse restore(Long notificationId, Long userId) {
        UserNotification notification = loadOwned(notificationId, userId);
        notification.restore();
        return toResponse(notification);
    }

    private UserNotification loadOwned(Long notificationId, Long userId) {
        UserNotification notification = repository.findDetailById(notificationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Obaveštenje nije pronađeno: " + notificationId));

        if (!notification.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovom obaveštenju.");
        }

        return notification;
    }

    private UserNotificationResponse toResponse(UserNotification n) {
        NotificationEvent event = n.getNotificationEvent();
        return new UserNotificationResponse(
                n.getId(),
                event.getType(),
                event.getTitle(),
                event.getMessage(),
                event.getEntityType(),
                event.getEntityId(),
                n.getReadAt() != null,
                n.getDismissedAt() != null,
                n.getCreatedAt()
        );
    }
}
