package com.aleksandarparipovic.marel_app.auth.google;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use CSRF state values for the Google OAuth redirect round-trip.
 * In-memory is fine — a handful of concurrent login attempts, few-minute TTL, no need
 * to survive a restart.
 */
@Component
public class OAuthStateStore {

    private static final long TTL_MILLIS = 5 * 60 * 1000;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> issued = new ConcurrentHashMap<>();

    public record Entry(String clientType, String intent, Instant issuedAt) {
    }

    public String issue(String clientType, String intent) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        issued.put(state, new Entry(clientType, intent, Instant.now()));
        return state;
    }

    /**
     * Consumes the state if valid and returns the (clientType, intent) it was issued
     * for, or empty if the state is missing/expired.
     */
    public java.util.Optional<Entry> consumeIfValid(String state) {
        Entry entry = issued.remove(state);
        if (entry == null || entry.issuedAt().plusMillis(TTL_MILLIS).isBefore(Instant.now())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(entry);
    }
}
