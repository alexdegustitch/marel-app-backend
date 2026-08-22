package com.aleksandarparipovic.marel_app.auth.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-in rate limit, exercised on a controlled clock.
 *
 * <p>Most of what is asserted here is what must NOT happen: a working account is
 * never slowed down, and a block ends on its own instead of being pushed forward
 * by the attempts it is already refusing.
 */
class AuthAttemptLimiterTest {

    private static final String CALLER = "10.0.0.1";

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private AuthAttemptLimiter limiter(int maxFailures, long windowSeconds, long blockSeconds) {
        return new AuthAttemptLimiter(maxFailures, windowSeconds, blockSeconds, now::get);
    }

    private void advanceSeconds(long seconds) {
        now.addAndGet(seconds * 1000L);
    }

    @Test
    @DisplayName("blocks only once the threshold is reached")
    void blocksAfterThreshold() {
        AuthAttemptLimiter limiter = limiter(3, 300, 600);

        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);
        assertThat(limiter.blockedForSeconds(CALLER)).isZero();

        limiter.recordFailure(CALLER);
        assertThat(limiter.blockedForSeconds(CALLER)).isEqualTo(600);
    }

    @Test
    @DisplayName("a correct sign-in clears the failures before it")
    void successClearsHistory() {
        AuthAttemptLimiter limiter = limiter(3, 300, 600);

        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);
        limiter.recordSuccess(CALLER);

        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);
        assertThat(limiter.blockedForSeconds(CALLER)).isZero();
    }

    @Test
    @DisplayName("failures spread beyond the window never accumulate")
    void windowExpires() {
        AuthAttemptLimiter limiter = limiter(3, 300, 600);

        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);

        advanceSeconds(301);

        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);
        assertThat(limiter.blockedForSeconds(CALLER)).isZero();
    }

    @Test
    @DisplayName("an active block is not extended by further attempts")
    void blockIsNotExtended() {
        AuthAttemptLimiter limiter = limiter(3, 300, 600);

        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);

        advanceSeconds(500);
        limiter.recordFailure(CALLER);
        assertThat(limiter.blockedForSeconds(CALLER)).isEqualTo(100);

        advanceSeconds(101);
        assertThat(limiter.blockedForSeconds(CALLER)).isZero();
    }

    @Test
    @DisplayName("one caller's block does not touch another's")
    void callersAreIndependent() {
        AuthAttemptLimiter limiter = limiter(2, 300, 600);

        limiter.recordFailure(CALLER);
        limiter.recordFailure(CALLER);

        assertThat(limiter.blockedForSeconds(CALLER)).isPositive();
        assertThat(limiter.blockedForSeconds("10.0.0.2")).isZero();
    }
}
