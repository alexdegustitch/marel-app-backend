package com.aleksandarparipovic.marel_app.user_session;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.auth.JwtAuthenticationFilter;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.user_session.dto.UserSessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Session and presence endpoints.
 *
 * <p>The session is always taken from the verified access token, never from the
 * request — a client that could name a session could forge another user's presence
 * or end their session.
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class UserSessionController {

    private final UserSessionService service;
    private final CurrentUserService currentUserService;
    private final PermissionService permissionService;

    /**
     * Lightweight keep-alive. Updates last_seen_at only; it never creates a session,
     * so a stale client cannot resurrect a revoked login.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(HttpServletRequest request) {
        String sessionId = sessionIdOf(request);

        if (sessionId != null) {
            service.heartbeat(sessionId);
        }

        // Echo the interval so the client has one source of truth for its cadence
        // instead of hard-coding it alongside the server's threshold.
        return ResponseEntity.ok(Map.of(
                "heartbeatSeconds", service.getHeartbeatSeconds()));
    }

    @GetMapping("/me")
    public ResponseEntity<List<UserSessionResponse>> mySessions(HttpServletRequest request) {
        return ResponseEntity.ok(service.listForUser(
                currentUserService.getCurrentUserId(), sessionIdOf(request)));
    }

    /**
     * Ends a session. A user may always end their own; ending someone else's
     * requires USER_SESSION_REVOKE, checked inside the service against ownership.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@PathVariable Long sessionId) {
        service.revoke(
                sessionId,
                currentUserService.getCurrentUserId(),
                permissionService.hasPermission(AppPermission.USER_SESSION_REVOKE));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/online")
    public ResponseEntity<List<Long>> onlineUsers(@RequestParam List<Long> userIds) {
        return ResponseEntity.ok(service.onlineUserIds(userIds));
    }

    private static String sessionIdOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.SESSION_ID_ATTRIBUTE);
        return attribute instanceof String sessionId ? sessionId : null;
    }
}
