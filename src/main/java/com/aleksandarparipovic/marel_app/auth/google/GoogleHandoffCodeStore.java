package com.aleksandarparipovic.marel_app.auth.google;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After the browser-side OAuth round-trip resolves a user, the backend hands the
 * Electron app a short-lived one-time code (via the marel:// deep link) instead of
 * putting real tokens in a URL. The renderer immediately exchanges it for the actual
 * access/refresh tokens over a normal POST request.
 */
@Component
public class GoogleHandoffCodeStore {

    private static final long TTL_MILLIS = 2 * 60 * 1000;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    private record Entry(Long userId, Instant issuedAt) {
    }

    public String issue(Long userId) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        codes.put(code, new Entry(userId, Instant.now()));
        return code;
    }

    public Optional<Long> consume(String code) {
        Entry entry = codes.remove(code);
        if (entry == null || entry.issuedAt().plusMillis(TTL_MILLIS).isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }
}
