package com.aleksandarparipovic.marel_app.auth.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Refuses credential guessing on the endpoints that accept credentials.
 *
 * <p>These paths are {@code permitAll} — they must be, nobody can authenticate
 * before signing in — which makes them the one part of the application the whole
 * internet may call. A wrong password is cheap for the caller and expensive here
 * (bcrypt), so unlimited attempts are both a way in and a way to load the server.
 *
 * <p>Runs as a filter rather than inside {@code AuthService} so a blocked caller
 * is turned away before password hashing, and so every credential endpoint is
 * covered by one rule instead of each remembering to ask.
 */
@Slf4j
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/google/exchange",
            "/api/auth/google/complete-registration"
    );

    private final AuthAttemptLimiter limiter;

    /**
     * Behind Nginx every request arrives from 127.0.0.1, so the real address has
     * to come from X-Forwarded-For — but that header is caller-supplied, and
     * trusting it on a directly exposed server would let an attacker reset its
     * own counter by making one up per request. It is therefore off by default
     * and turned on only where a reverse proxy is known to overwrite it.
     */
    private final boolean trustForwardedHeader;

    public AuthRateLimitFilter(
            AuthAttemptLimiter limiter,
            @Value("${app.security.login-rate-limit.trust-forwarded-header:false}") boolean trustForwardedHeader
    ) {
        this.limiter = limiter;
        this.trustForwardedHeader = trustForwardedHeader;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && LIMITED_PATHS.contains(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String key = callerKey(request);

        long waitSeconds = limiter.blockedForSeconds(key);
        if (waitSeconds > 0) {
            log.warn("[AuthRateLimit] Blocked {} on {} for another {}s",
                    key, request.getRequestURI(), waitSeconds);
            reject(response, waitSeconds);
            return;
        }

        filterChain.doFilter(request, response);

        // Any 4xx/5xx on a credential endpoint counts as a failed attempt: wrong
        // password, unknown user and expired hand-off code are all indistinguishable
        // to the caller by design, and should be indistinguishable here too.
        if (response.getStatus() >= 400) {
            limiter.recordFailure(key);
        } else {
            limiter.recordSuccess(key);
        }
    }

    private String callerKey(HttpServletRequest request) {
        if (trustForwardedHeader) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, long waitSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(waitSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Same shape as GlobalExceptionHandler, so the client renders it like any
        // other refusal instead of falling into a generic "network error".
        response.getWriter().write(
                "{\"timestamp\":\"" + LocalDateTime.now() + "\","
                        + "\"error\":\"Previše neuspešnih pokušaja. Pokušajte ponovo za "
                        + Math.max(1, waitSeconds / 60) + " min.\"}");
    }
}
