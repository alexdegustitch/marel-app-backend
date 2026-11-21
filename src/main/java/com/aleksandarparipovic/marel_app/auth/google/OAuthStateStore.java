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

    /**
     * The intent of joining an already signed-in profile to a Google account.
     *
     * <p>Here rather than beside the service that uses it: the intents are a
     * property of this round-trip, and keeping it here is what stops the
     * authentication package having to import the account package to read its
     * own callback's state.
     */
    public static final String INTENT_LINK = "link";

    private static final long TTL_MILLIS = 5 * 60 * 1000;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> issued = new ConcurrentHashMap<>();

    /**
     * @param userId who asked, for the {@code link} intent only — joining a Google
     *               account to a profile has to know WHICH profile, and the answer
     *               is settled here, from the session that started it, rather than
     *               taken from anything the browser carries back. NULL for sign-in
     *               and registration, which belong to nobody yet.
     */
    public record Entry(String clientType, String intent, Long userId, Instant issuedAt) {
    }

    public String issue(String clientType, String intent) {
        return issue(clientType, intent, null);
    }

    public String issue(String clientType, String intent, Long userId) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        issued.put(state, new Entry(clientType, intent, userId, Instant.now()));
        return state;
    }

    /**
     * Consumes the state if valid and returns the (clientType, intent, userId) it was
     * issued for, or empty if the state is missing/expired.
     */
    public java.util.Optional<Entry> consumeIfValid(String state) {
        Entry entry = issued.remove(state);
        if (entry == null || entry.issuedAt().plusMillis(TTL_MILLIS).isBefore(Instant.now())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(entry);
    }
}
