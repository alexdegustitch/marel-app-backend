package com.aleksandarparipovic.marel_app.auth.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts failed credential attempts per caller and blocks the caller once there
 * are too many of them in a short window.
 *
 * <p><b>Only failures count.</b> Somebody who signs in correctly is never slowed
 * down no matter how often they do it, and a successful sign-in clears the
 * caller's history. This matters here because a factory sits behind one public
 * address: counting every attempt would let one person's typing lock out the
 * whole floor.
 *
 * <p>An already blocked caller does not accumulate further — its requests are
 * refused before they reach the endpoint, so the block expires at a fixed time
 * instead of being pushed forward indefinitely by an attacker who keeps trying.
 *
 * <p>State is in memory, like {@code OAuthStateStore}: it is a rate limit, not a
 * record, and losing it on restart only means a blocked caller gets its attempts
 * back. Running more than one instance would give each its own count — worth
 * knowing before this is ever put behind a load balancer.
 */
@Component
public class AuthAttemptLimiter {

    /**
     * Bound on distinct tracked callers. Without it, addresses are attacker-chosen
     * map keys and the limiter itself becomes the way to exhaust memory.
     */
    private static final int MAX_TRACKED_KEYS = 20_000;

    private final int maxFailures;
    private final long windowMs;
    private final long blockMs;
    private final LongSupplier clock;

    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    @Autowired
    public AuthAttemptLimiter(
            @Value("${app.security.login-rate-limit.max-failures:15}") int maxFailures,
            @Value("${app.security.login-rate-limit.window-seconds:300}") long windowSeconds,
            @Value("${app.security.login-rate-limit.block-seconds:600}") long blockSeconds
    ) {
        this(maxFailures, windowSeconds, blockSeconds, System::currentTimeMillis);
    }

    /** Test seam: a controllable clock, so the window can be exercised without sleeping. */
    AuthAttemptLimiter(int maxFailures, long windowSeconds, long blockSeconds, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.windowMs = windowSeconds * 1000L;
        this.blockMs = blockSeconds * 1000L;
        this.clock = clock;
    }

    /** @return seconds the caller must wait, or 0 when it is free to try. */
    public long blockedForSeconds(String key) {
        Attempts current = attempts.get(key);
        if (current == null) {
            return 0;
        }
        long remainingMs = current.blockedUntilMs - clock.getAsLong();
        return remainingMs > 0 ? (remainingMs + 999) / 1000 : 0;
    }

    public void recordFailure(String key) {
        long now = clock.getAsLong();

        attempts.compute(key, (ignored, current) -> {
            if (current == null) {
                return new Attempts(1, now, 0);
            }
            // The block is checked BEFORE the window, and in this order for a
            // reason: a block outlives the window that caused it, so testing the
            // window first would let a caller who simply keeps guessing past the
            // window's end reset itself out of its own block.
            if (current.blockedUntilMs > now) {
                return current;
            }
            // A block that has been served out starts the caller over rather than
            // resuming a count that is already at the threshold — otherwise the
            // very next wrong password would re-block immediately.
            if (current.blockedUntilMs > 0 || now - current.windowStartMs >= windowMs) {
                return new Attempts(1, now, 0);
            }
            int failures = current.failures + 1;
            long blockedUntil = failures >= maxFailures ? now + blockMs : 0;
            return new Attempts(failures, current.windowStartMs, blockedUntil);
        });

        evictIfCrowded(now);
    }

    /** A correct sign-in is proof this caller is not guessing. */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    private void evictIfCrowded(long now) {
        if (attempts.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        attempts.values().removeIf(a -> a.blockedUntilMs <= now && now - a.windowStartMs >= windowMs);
    }

    private record Attempts(int failures, long windowStartMs, long blockedUntilMs) {
    }
}
