package com.aleksandarparipovic.marel_app.dashboard.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs the day's analytics once, in the morning, so nobody's home screen has to.
 *
 * <p>05:15 Europe/Belgrade: after the night's recalculations have settled and
 * before the first shift's supervisor opens the board. The hour follows the one
 * scheduling convention this project already has — {@code EmployeeDeactivationScheduler}
 * runs at 06:30 in the same zone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardInsightJob {

    private final DashboardInsightComputeService computeService;
    private final DashboardInsightRepository repository;

    /**
     * Whether a server that starts with no snapshot for today should compute one.
     *
     * <p>On in production, so a restart or a fresh install shows figures instead of
     * "još nije izračunato" until the next morning. Off in tests, where the board's
     * data is set up by the test itself and a startup sweep would race it.
     */
    @Value("${app.dashboard.insights.compute-on-startup:true}")
    private boolean computeOnStartup;

    @Scheduled(cron = "0 15 5 * * *", zone = "Europe/Belgrade")
    public void computeDaily() {
        run(LocalDate.now(), "raspored");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void computeOnStartupIfMissing() {
        if (!computeOnStartup) {
            return;
        }

        LocalDate today = LocalDate.now();
        boolean alreadyHaveToday = repository
                .findLatest(DashboardInsightKey.MOST_WORKED_OPERATIONS, Object.class)
                .map(stored -> today.equals(stored.computedFor()))
                .orElse(false);

        if (alreadyHaveToday) {
            return;
        }

        run(today, "pokretanje");
    }

    /**
     * Recomputes on demand.
     *
     * <p>Exists because a threshold that produced nothing is otherwise impossible to
     * try out: without this, changing one means waiting until tomorrow morning to see
     * what it does.
     */
    public void recomputeNow() {
        run(LocalDate.now(), "ručno");
    }

    private void run(LocalDate day, String trigger) {
        long startedAt = System.nanoTime();
        try {
            computeService.computeFor(day);
            log.info("[DashboardInsight] Analitika za {} izračunata ({}), {} ms.",
                    day, trigger, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            // A failed snapshot must never take the application down or block a
            // startup: the board falls back to the last day that succeeded, and says
            // which day that was.
            log.error("[DashboardInsight] Analitika za {} nije izračunata ({}).", day, trigger, e);
        }
    }
}
