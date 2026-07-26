package com.aleksandarparipovic.marel_app.user_notification;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.user_notification.dto.UserNotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The notification centre. Scoped entirely to the authenticated user — there is
 * no route that takes another user's id.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserNotificationService service;
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<Page<UserNotificationResponse>> list(
            @RequestParam(defaultValue = "false") boolean includeDismissed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return ResponseEntity.ok(service.list(
                currentUserService.getCurrentUserId(),
                includeDismissed,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"))
        ));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of(
                "count", service.unreadCount(currentUserService.getCurrentUserId())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<UserNotificationResponse> markRead(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.markRead(id, currentUserService.getCurrentUserId()));
    }

    @PostMapping("/{id}/unread")
    public ResponseEntity<UserNotificationResponse> markUnread(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.markUnread(id, currentUserService.getCurrentUserId()));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead() {
        return ResponseEntity.ok(Map.of(
                "updated", service.markAllRead(currentUserService.getCurrentUserId())));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<UserNotificationResponse> dismiss(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.dismiss(id, currentUserService.getCurrentUserId()));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<UserNotificationResponse> restore(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.restore(id, currentUserService.getCurrentUserId()));
    }
}
