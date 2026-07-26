package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.auth.dto.GoogleCompleteRegistrationRequest;
import com.aleksandarparipovic.marel_app.auth.dto.GoogleExchangeRequest;
import com.aleksandarparipovic.marel_app.auth.dto.LoginRequest;
import com.aleksandarparipovic.marel_app.auth.dto.LoginResponse;
import com.aleksandarparipovic.marel_app.auth.dto.RefreshTokenRequest;
import com.aleksandarparipovic.marel_app.auth.dto.RegisterRequest;
import com.aleksandarparipovic.marel_app.auth.dto.RegisterResponse;
import com.aleksandarparipovic.marel_app.auth.google.GoogleHandoffCodeStore;
import com.aleksandarparipovic.marel_app.auth.google.GoogleOAuthService;
import com.aleksandarparipovic.marel_app.auth.google.GooglePendingRegistrationStore;
import com.aleksandarparipovic.marel_app.auth.google.GoogleUserInfo;
import com.aleksandarparipovic.marel_app.auth.google.OAuthStateStore;
import com.aleksandarparipovic.marel_app.role.RoleService;
import com.aleksandarparipovic.marel_app.role.dto.RoleDto;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RoleService roleService;
    private final GoogleOAuthService googleOAuthService;
    private final OAuthStateStore oAuthStateStore;
    private final GoogleHandoffCodeStore googleHandoffCodeStore;
    private final GooglePendingRegistrationStore googlePendingRegistrationStore;

    @Value("${app.deep-link.scheme:marel}")
    private String deepLinkScheme;

    @Value("${app.web-app-url:http://localhost:5123}")
    private String webAppUrl;

    @Value("${app.security.refresh-token.cookie-name:refresh_token}")
    private String refreshTokenCookieName;

    @Value("${app.security.refresh-token.cookie-secure:false}")
    private boolean refreshTokenCookieSecure;

    @Value("${app.security.refresh-token.cookie-same-site:Lax}")
    private String refreshTokenCookieSameSite;

    @Value("${app.security.refresh-token.ttl-seconds:2592000}")
    private long refreshTokenTtlSeconds;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody @Valid LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        LoginResponse response = authService.login(
                request.getUsername(),
                request.getPassword(),
                clientIp(httpRequest),
                userAgent(httpRequest)
        );
        writeRefreshCookie(httpResponse, response.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/registrable-roles")
    public ResponseEntity<List<RoleDto>> registrableRoles() {
        return ResponseEntity.ok(roleService.findRegistrable());
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody @Valid RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    private static final String CLIENT_DESKTOP = "desktop";
    private static final String CLIENT_WEB = "web";
    private static final String INTENT_LOGIN = "login";
    private static final String INTENT_REGISTER = "register";

    @GetMapping("/google/login")
    public ResponseEntity<Void> googleLogin(
            @RequestParam(required = false, defaultValue = CLIENT_DESKTOP) String client,
            @RequestParam(required = false, defaultValue = INTENT_LOGIN) String intent
    ) {
        if (!googleOAuthService.isConfigured()) {
            throw new IllegalStateException("Google prijava nije podešena na serveru.");
        }

        String clientType = CLIENT_WEB.equals(client) ? CLIENT_WEB : CLIENT_DESKTOP;
        String intentType = INTENT_REGISTER.equals(intent) ? INTENT_REGISTER : INTENT_LOGIN;
        String state = oAuthStateStore.issue(clientType, intentType);
        URI redirectUri = googleOAuthService.buildAuthorizationUrl(state);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirectUri)
                .build();
    }

    @GetMapping("/google/callback")
    public ResponseEntity<?> googleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error
    ) {
        OAuthStateStore.Entry entry = state == null ? null : oAuthStateStore.consumeIfValid(state).orElse(null);

        if (error != null || code == null || entry == null) {
            // Unknown client/intent (missing/expired state) — desktop landing page is the
            // safer default since a bare redirect to a plain web URL would silently strand
            // a desktop-app user with no way back into the app.
            String clientType = entry != null ? entry.clientType() : CLIENT_DESKTOP;
            return respondWithResult(clientType, "error", "google_failed");
        }

        try {
            GoogleUserInfo info = googleOAuthService.exchangeCodeForUserInfo(code);

            java.util.Optional<User> existing = authService.findGoogleUserForLogin(info.email());

            if (existing.isEmpty()) {
                if (!INTENT_REGISTER.equals(entry.intent())) {
                    // Login intent must never provision an account — a missing account is a
                    // distinct, explicit error, not a silent signup.
                    return respondWithResult(entry.clientType(), "error", "no_account");
                }
                // No local account yet: don't create one blind — the user still needs to pick
                // a role (and optionally enter a phone number), same as normal registration.
                String registerCode = googlePendingRegistrationStore.issue(
                        new GooglePendingRegistrationStore.GoogleProfile(info.email(), info.givenName(), info.familyName())
                );
                return respondWithResult(entry.clientType(), "registerCode", registerCode);
            }

            User user = existing.get();
            if (!Boolean.TRUE.equals(user.getActive()) || user.getArchivedAt() != null) {
                return respondWithResult(entry.clientType(), "error", "pending_approval");
            }

            String handoffCode = googleHandoffCodeStore.issue(user.getId());
            return respondWithResult(entry.clientType(), "code", handoffCode);
        } catch (Exception ex) {
            log.error("[AuthController] Google OAuth callback failed", ex);
            return respondWithResult(entry.clientType(), "error", "google_failed");
        }
    }

    private ResponseEntity<?> respondWithResult(String clientType, String paramName, String paramValue) {
        String encodedValue = java.net.URLEncoder.encode(paramValue, java.nio.charset.StandardCharsets.UTF_8);

        if (CLIENT_WEB.equals(clientType)) {
            // Plain browser: a normal same-origin HTTP redirect back into the web app works
            // natively — no custom scheme involved.
            // HashRouter: query params must live after the "#" or react-router never sees them.
            URI target = URI.create(webAppUrl + "/#/auth/google-callback?" + paramName + "=" + encodedValue);
            return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
        }
        // Desktop app: browsers do not reliably follow a bare HTTP 302 to a custom URL scheme
        // (the tab just hangs with no error) — a small landing page that redirects via
        // window.location.href, with a manual fallback link, is the reliable pattern.
        return deepLinkLandingPage(deepLinkScheme + "://auth-callback?" + paramName + "=" + encodedValue);
    }

    @PostMapping("/google/complete-registration")
    public ResponseEntity<RegisterResponse> completeGoogleRegistration(
            @RequestBody @Valid GoogleCompleteRegistrationRequest request
    ) {
        GooglePendingRegistrationStore.GoogleProfile profile = googlePendingRegistrationStore
                .consume(request.getRegisterCode())
                .orElseThrow(() -> new IllegalArgumentException("Kod je istekao ili je nevažeći. Pokušajte ponovo preko Google-a."));

        RegisterResponse response = authService.completeGoogleRegistration(
                profile.email(), profile.firstName(), profile.lastName(),
                request.getRoleId(), request.getMobilePhone()
        );
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<String> deepLinkLandingPage(String deepLinkUrl) {
        String safeUrl = deepLinkUrl.replace("\"", "%22");
        String html = """
                <!DOCTYPE html>
                <html lang="sr">
                <head>
                  <meta charset="UTF-8">
                  <title>Marel</title>
                  <style>
                    :root { color-scheme: light; }
                    body {
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                      display: flex; align-items: center; justify-content: center;
                      height: 100vh; margin: 0;
                      background: radial-gradient(circle at 30% 20%, #eef1ff 0%, #f4f6fb 45%, #eef0f5 100%);
                    }
                    .card {
                      background: #fff; border-radius: 16px; padding: 40px 44px;
                      box-shadow: 0 10px 40px rgba(30, 40, 90, .08);
                      text-align: center; max-width: 360px;
                    }
                    .brand {
                      display: inline-flex; align-items: center; justify-content: center;
                      width: 40px; height: 40px; border-radius: 10px;
                      background: linear-gradient(135deg, #4c6ef5, #4263eb);
                      margin-bottom: 18px;
                    }
                    .spinner {
                      width: 22px; height: 22px; margin: 4px auto 18px;
                      border: 3px solid #e3e6f5; border-top-color: #4c6ef5;
                      border-radius: 50%%; animation: spin 0.8s linear infinite;
                    }
                    @keyframes spin { to { transform: rotate(360deg); } }
                    h1 { font-size: 16px; font-weight: 600; color: #212529; margin: 0 0 6px; }
                    p { font-size: 13.5px; color: #868e96; margin: 0; line-height: 1.5; }
                    a { color: #4c6ef5; text-decoration: none; font-weight: 500; }
                    a:hover { text-decoration: underline; }
                  </style>
                  <script>
                    window.location.href = "%s";
                  </script>
                </head>
                <body>
                  <div class="card">
                    <div class="brand">
                      <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                        <rect x="1" y="8" width="3.5" height="7" rx="1" fill="white" opacity=".85"/>
                        <rect x="6.25" y="4" width="3.5" height="11" rx="1" fill="white"/>
                        <rect x="11.5" y="1" width="3.5" height="14" rx="1" fill="white" opacity=".7"/>
                      </svg>
                    </div>
                    <div class="spinner"></div>
                    <h1>Vraćanje u Marel aplikaciju</h1>
                    <p>
                      Ako se aplikacija nije automatski otvorila,
                      <a href="%s">kliknite ovde</a>.
                    </p>
                  </div>
                </body>
                </html>
                """.formatted(safeUrl, safeUrl);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .body(html);
    }

    @PostMapping("/google/exchange")
    public ResponseEntity<LoginResponse> googleExchange(
            @RequestBody @Valid GoogleExchangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        Long userId = googleHandoffCodeStore.consume(request.getCode())
                .orElseThrow(() -> new IllegalArgumentException("Kod je istekao ili je nevažeći"));

        LoginResponse response = authService.issueTokensForUserId(
                userId,
                clientIp(httpRequest),
                userAgent(httpRequest)
        );
        writeRefreshCookie(httpResponse, response.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String refreshToken = resolveRefreshToken(request, httpRequest);
        LoginResponse response = authService.refresh(
                refreshToken,
                clientIp(httpRequest),
                userAgent(httpRequest)
        );
        writeRefreshCookie(httpResponse, response.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String refreshToken = resolveRefreshToken(request, httpRequest);
        authService.logout(refreshToken);
        clearRefreshCookie(httpResponse);
        return ResponseEntity.noContent().build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }

    private String resolveRefreshToken(RefreshTokenRequest request, HttpServletRequest httpRequest) {
        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            return request.getRefreshToken();
        }

        Cookie[] cookies = httpRequest.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (refreshTokenCookieName.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }

        throw new IllegalArgumentException("Refresh token is required");
    }

    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(refreshTokenCookieName, refreshToken)
                .httpOnly(true)
                .secure(refreshTokenCookieSecure)
                .sameSite(refreshTokenCookieSameSite)
                .path("/api/auth")
                .maxAge(refreshTokenTtlSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(refreshTokenCookieName, "")
                .httpOnly(true)
                .secure(refreshTokenCookieSecure)
                .sameSite(refreshTokenCookieSameSite)
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
