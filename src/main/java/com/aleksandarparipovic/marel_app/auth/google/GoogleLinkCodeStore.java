package com.aleksandarparipovic.marel_app.auth.google;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The window between "Google confirmed who this is" and "the signed-in person
 * accepted the join".
 *
 * <p>The callback could apply the join itself — the state already says which
 * profile asked — but then the join would be applied by an unauthenticated
 * request arriving from a browser redirect. Handing back a one-time code and
 * having the APPLICATION post it, with its token, means the last step is taken
 * by a session that is demonstrably the profile's own. That is the same shape
 * {@link GoogleHandoffCodeStore} already uses for sign-in, for the same reason.
 *
 * <p>The code carries the intended profile as well as the Google identity, and
 * the service refuses it if the caller is anybody else — so a code that leaks or
 * is replayed cannot attach somebody's Google account to another profile.
 */
@Component
public class GoogleLinkCodeStore {

    private static final long TTL_MILLIS = 2 * 60 * 1000;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    /** The Google identity to join, and the profile that asked for it. */
    public record PendingLink(Long userId, String subject, String email) {
    }

    private record Entry(PendingLink link, Instant issuedAt) {
    }

    public String issue(PendingLink link) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        codes.put(code, new Entry(link, Instant.now()));
        return code;
    }

    public Optional<PendingLink> consume(String code) {
        Entry entry = code == null ? null : codes.remove(code);
        if (entry == null || entry.issuedAt().plusMillis(TTL_MILLIS).isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.link());
    }
}
