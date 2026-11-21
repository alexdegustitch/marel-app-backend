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
    private final GoogleAccountLinkService googleAccountLinkService;
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

    /**
     * Whether a Google account is joined to this profile, and whether it could be.
     *
     * <p>Under {@code /api/me} rather than {@code /api/auth}: this is a fact about
     * the caller's own account, and it is the only place the joined address is
     * served from — it is never put in {@link UserDto}, which feeds the staff
     * directory.
     */
    @GetMapping("/google")
    public ResponseEntity<GoogleLinkStatusResponse> googleLink() {
        return ResponseEntity.ok(googleAccountLinkService.status(currentUserService.getCurrentUserId()));
    }

    /**
     * Where to send the person so Google can confirm who they are.
     *
     * <p>{@code client} says how the round-trip should come back: a desktop build
     * finishes through the {@code marel://} deep link, a browser through a plain
     * redirect. Same two values the sign-in flow already uses, and anything else
     * is read as "desktop" there — so it is read as "web" here only when it says
     * so, keeping the two in step.
     */
    @PostMapping("/google/link-start")
    public ResponseEntity<GoogleLinkStartResponse> startGoogleLink(
            @RequestParam(required = false, defaultValue = "desktop") String client
    ) {
        return ResponseEntity.ok(googleAccountLinkService.startLink(
                currentUserService.getCurrentUserId(),
                "web".equals(client) ? "web" : "desktop"));
    }

    /**
     * Applies the join, with the one-time code the round-trip handed back.
     *
     * <p>This is the step that makes the join the profile's OWN act: it arrives on
     * an authenticated request, and the service refuses a code that was issued for
     * anybody else.
     */
    @PostMapping("/google/link")
    public ResponseEntity<GoogleLinkStatusResponse> completeGoogleLink(
            @RequestBody @Valid GoogleLinkRequest request
    ) {
        return ResponseEntity.ok(googleAccountLinkService.completeLink(
                currentUserService.getCurrentUserId(), request.getCode()));
    }

    /**
     * Severs the join. Needs the password, and refuses while the account has none.
     *
     * <p>A body on a DELETE, which is unusual — but the alternative is the password
     * in a query string, and a credential in a URL ends up in every access log and
     * every browser history.
     */
    @DeleteMapping("/google")
    public ResponseEntity<GoogleLinkStatusResponse> unlinkGoogle(
            @RequestBody @Valid GoogleUnlinkRequest request
    ) {
        return ResponseEntity.ok(googleAccountLinkService.unlink(
                currentUserService.getCurrentUserId(), request.getCurrentPassword()));
    }

    /** Put on the request by {@link JwtAuthenticationFilter} from the token's `sid` claim. */
    private static String sessionIdOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.SESSION_ID_ATTRIBUTE);
        return attribute instanceof String sessionId ? sessionId : null;
    }
}
