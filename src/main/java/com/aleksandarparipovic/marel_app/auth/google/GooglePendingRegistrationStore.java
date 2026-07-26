package com.aleksandarparipovic.marel_app.auth.google;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When "Continue with Google" is used from the registration screen and the Google
 * account has no matching local user, the backend doesn't create one yet — it needs
 * the role and (optional) phone number the user still has to pick. This holds the
 * Google-verified identity for that short window between the OAuth round-trip and
 * the "complete your profile" form submission.
 */
@Component
public class GooglePendingRegistrationStore {

    private static final long TTL_MILLIS = 10 * 60 * 1000;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();

    public record GoogleProfile(String email, String firstName, String lastName) {
    }

    private record Entry(GoogleProfile profile, Instant issuedAt) {
    }

    public String issue(GoogleProfile profile) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(code, new Entry(profile, Instant.now()));
        return code;
    }

    public Optional<GoogleProfile> consume(String code) {
        Entry entry = pending.remove(code);
        if (entry == null || entry.issuedAt().plusMillis(TTL_MILLIS).isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.profile());
    }
}
