package com.aleksandarparipovic.marel_app.assistant;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user, per-day counter of real assistant calls.
 *
 * <p>IN-MEMORY ONLY. The counts live in a {@link ConcurrentHashMap} keyed by
 * {@code userId + ":" + LocalDate.now()} and therefore RESET ON EVERY RESTART.
 * A persistent, audited quota table is a deliberate follow-up: it would be a
 * schema change, and schema changes need the owner's approval per the project
 * rules. Until then a restart simply grants everyone a fresh daily budget,
 * which is acceptable for a soft rate limit on an assistant feature.
 */
@Service
public class AssistantQuotaService {

    /** admin / supervisor get a larger daily budget than everyone else. */
    public static final int PRIVILEGED_DAILY_LIMIT = 15;
    public static final int DEFAULT_DAILY_LIMIT = 5;

    private final Map<String, Integer> countsByUserDay = new ConcurrentHashMap<>();

    /**
     * Count one real model call against today's budget, or reject it.
     *
     * <p>Atomic per key via {@link ConcurrentHashMap#compute}: two concurrent
     * calls cannot both squeeze past the last remaining slot. Increment happens
     * ONLY here, and this is invoked only when a genuine model call is about to
     * be made — never on the disabled or "no data" paths.
     *
     * @throws QuotaExceededException when the user is already at the limit
     */
    public void consumeOrThrow(Long userId, int dailyLimit) {
        LocalDate today = LocalDate.now();
        pruneOldDays(today);

        String key = userId + ":" + today;
        countsByUserDay.compute(key, (k, current) -> {
            int used = (current == null) ? 0 : current;
            if (used >= dailyLimit) {
                throw new QuotaExceededException(dailyLimit);
            }
            return used + 1;
        });
    }

    /** Drop yesterday's (and older) buckets so the map cannot grow unbounded. */
    private void pruneOldDays(LocalDate today) {
        String todaySuffix = ":" + today;
        for (Iterator<String> it = countsByUserDay.keySet().iterator(); it.hasNext(); ) {
            if (!it.next().endsWith(todaySuffix)) {
                it.remove();
            }
        }
    }
}
