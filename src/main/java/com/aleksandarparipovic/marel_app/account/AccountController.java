package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.account.dto.*;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.auth.JwtAuthenticationFilter;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The account, as its own owner.
 *
 * <p>Every route here is about the caller and takes no user id — the id comes
 * from the security context. That is deliberate and structural: there is nothing
 * in any of these paths that a caller could change to act on somebody else's
 * account.
 *
 * <p>Mounted at {@code /api/me} rather than under {@code /api/users}, because
 * {@code /api/users/**} is administrator-only in {@code SecurityConfig}. Nesting
 * self-service under it would have meant punching per-route holes in that rule,
 * and a rule full of exceptions is one that gets read wrongly.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final EmailChangeService emailChangeService;
    private final CurrentUserService currentUserService;

    /** Name, shown-as name and telephone. No administrator involved. */
    @PatchMapping("/profile")
    public ResponseEntity<UserDto> updateProfile(@RequestBody @Valid ProfileUpdateRequest request) {
        return ResponseEntity.ok(
                accountService.updateOwnProfile(currentUserService.getCurrentUserId(), request));
    }

    /** 204: there is nothing to hand back, and a password response would be a mistake waiting. */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody @Valid PasswordChangeRequest request) {
        accountService.changeOwnPassword(currentUserService.getCurrentUserId(), request);
        return ResponseEntity.noContent().build();
    }

    /**
     * The change waiting to be confirmed, or 204 when there is none.
     *
     * <p>Lets the screen come back to a half-finished change after a reload
     * instead of losing it — which would otherwise strand somebody holding a code
     * for a request the application no longer shows.
     */
    @GetMapping("/email-change")
    public ResponseEntity<PendingEmailChangeResponse> pendingEmailChange() {
        return emailChangeService.pending(currentUserService.getCurrentUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Verifies the password and sends a code to the NEW address. Nothing changes yet. */
    @PostMapping("/email-change")
    public ResponseEntity<PendingEmailChangeResponse> startEmailChange(
            @RequestBody @Valid EmailChangeStartRequest request
    ) {
        return ResponseEntity.ok(emailChangeService.start(
                currentUserService.getCurrentUserId(),
                request.getNewEmail(),
                request.getCurrentPassword()));
    }

    /**
     * Applies the change, if the code is right.
     *
     * <p>The request is needed for the session id: the session doing this stays
     * signed in while the others are ended, and that id travels as a claim on the
     * access token rather than in anything the client could choose.
     */
    @PostMapping("/email-change/confirm")
    public ResponseEntity<Void> confirmEmailChange(
            @RequestBody @Valid EmailChangeConfirmRequest request,
            HttpServletRequest httpRequest
    ) {
        emailChangeService.confirm(
                currentUserService.getCurrentUserId(),
                request.getCode(),
                sessionIdOf(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/email-change")
    public ResponseEntity<Void> cancelEmailChange() {
        emailChangeService.cancel(currentUserService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /** Put on the request by {@link JwtAuthenticationFilter} from the token's `sid` claim. */
    private static String sessionIdOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.SESSION_ID_ATTRIBUTE);
        return attribute instanceof String sessionId ? sessionId : null;
    }
}
