package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated background workers that continuously pull jobs from DB queue tables.
 * No in-memory task queue is used for business jobs.
 */
/**
 * Owns the background threads that drain the recalculation queues.
 *
 * <p><b>{@code app.recalc.enabled=false} keeps them from starting at all.</b>
 * Integration tests drive {@code DailyRecalcService.processJob} themselves, and a
 * worker running on its own timer races them for the same job — both claim it,
 * both try to create the month's {@code employee_records} row, and one loses on
 * {@code uq_employee_records_employee_start_date}. The outbox and delivery
 * workers are already neutralised in the test profile for the same reason; this
 * one had no switch.
 *
 * <p>Defaults to enabled, so nothing changes anywhere it is not set.
 */
@Component
@ConditionalOnProperty(name = "app.recalc.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DbQueueWorkerManager {

    private final DailyReportWorker dailyReportWorker;
    private final MonthlyReportWorker monthlyReportWorker;
    private final RecalcQueueService recalcQueueService;
    private final RecalcWorkerProperties properties;
    private final MeterRegistry meterRegistry;
    private final RecalcWorkerWakeSignal wakeSignal;

    private final List<Thread> threads = new ArrayList<>();
    private final AtomicLong dailyProcessedWindow = new AtomicLong();
    private final AtomicLong monthlyProcessedWindow = new AtomicLong();
    private volatile boolean running = true;
    private volatile boolean started;
    private volatile long lastMetricsLogAt = System.currentTimeMillis();
    private volatile long lastBacklogWarnAt = 0L;

    @EventListener(ApplicationReadyEvent.class)
    public void startWorkers() {
        if (started) {
            return;
        }
        started = true;

        Gauge.builder("recalc.queue.pending", recalcQueueService, svc -> svc.countPendingDaily())
                .tag("type", "daily")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.pending", recalcQueueService, svc -> svc.countPendingMonthly())
                .tag("type", "monthly")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.failed", recalcQueueService, svc -> svc.countFailedDaily())
                .tag("type", "daily")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.failed", recalcQueueService, svc -> svc.countFailedMonthly())
                .tag("type", "monthly")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.in_progress", recalcQueueService, svc -> svc.countInProgressDaily())
                .tag("type", "daily")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.in_progress", recalcQueueService, svc -> svc.countInProgressMonthly())
                .tag("type", "monthly")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.retrying", recalcQueueService, svc -> svc.countRetryingDaily())
                .tag("type", "daily")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.retrying", recalcQueueService, svc -> svc.countRetryingMonthly())
                .tag("type", "monthly")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.pending_age_seconds", recalcQueueService, svc -> svc.pendingAgeSecondsDaily())
                .tag("type", "daily")
                .register(meterRegistry);
        Gauge.builder("recalc.queue.pending_age_seconds", recalcQueueService, svc -> svc.pendingAgeSecondsMonthly())
                .tag("type", "monthly")
                .register(meterRegistry);

        int dailyThreads = Math.max(1, properties.getDailyThreads());
        int monthlyThreads = Math.max(1, properties.getMonthlyThreads());

        for (int i = 1; i <= dailyThreads; i++) {
            String workerId = "daily-worker-" + i;
            Thread thread = Thread.ofPlatform().name(workerId).start(() -> dailyLoop(workerId));
            threads.add(thread);
        }
        for (int i = 1; i <= monthlyThreads; i++) {
            String workerId = "monthly-worker-" + i;
            Thread thread = Thread.ofPlatform().name(workerId).start(() -> monthlyLoop(workerId));
            threads.add(thread);
        }

        log.info("Started DB queue workers: daily={} monthly={} dailyBatch={} monthlyBatch={} loopBudgetMs={}",
                dailyThreads,
                monthlyThreads,
                properties.getDailyBatch(),
                properties.getMonthlyBatch(),
                properties.getLoopTimeBudgetMs());
    }

    @PreDestroy
    public void stopWorkers() {
        running = false;
        wakeSignal.signalAll();
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void dailyLoop(String workerId) {
        long minIdleMs = Math.max(10L, properties.getMinIdleSleepMs());
        long maxIdleMs = Math.max(minIdleMs, properties.getMaxIdleSleepMs());
        long idleMs = minIdleMs;
        int loop = 0;
        while (running) {
            try {
                if (loop++ % 20 == 0) {
                    recalcQueueService.requeueStuckDailyJobs(Duration.ofSeconds(properties.getStuckTimeoutSeconds()), properties.getStuckRecoveryBatch());
                }

                int processed = dailyReportWorker.processBatch(
                        properties.getDailyBatch(),
                        workerId,
                        Duration.ofMillis(properties.getLoopTimeBudgetMs())
                );
                dailyProcessedWindow.addAndGet(processed);
                maybeLogMetrics();

                if (processed == 0) {
                    waitForWakeOrTimeout(idleMs);
                    idleMs = Math.min(maxIdleMs, Math.max(minIdleMs, idleMs * 2));
                } else {
                    idleMs = minIdleMs;
                }
            } catch (Exception e) {
                log.error("Daily worker loop {} failed", workerId, e);
                waitForWakeOrTimeout(Math.min(maxIdleMs, Math.max(minIdleMs, 500)));
            }
        }
    }

    private void monthlyLoop(String workerId) {
        long minIdleMs = Math.max(10L, properties.getMinIdleSleepMs());
        long maxIdleMs = Math.max(minIdleMs, properties.getMaxIdleSleepMs());
        long idleMs = minIdleMs;
        int loop = 0;
        while (running) {
            try {
                if (loop++ % 20 == 0) {
                    recalcQueueService.requeueStuckMonthlyJobs(Duration.ofSeconds(properties.getStuckTimeoutSeconds()), properties.getStuckRecoveryBatch());
                }

                int processed = monthlyReportWorker.processBatch(
                        properties.getMonthlyBatch(),
                        workerId,
                        Duration.ofMillis(properties.getLoopTimeBudgetMs())
                );
                monthlyProcessedWindow.addAndGet(processed);
                maybeLogMetrics();

                if (processed == 0) {
                    waitForWakeOrTimeout(idleMs);
                    idleMs = Math.min(maxIdleMs, Math.max(minIdleMs, idleMs * 2));
                } else {
                    idleMs = minIdleMs;
                }
            } catch (Exception e) {
                log.error("Monthly worker loop {} failed", workerId, e);
                waitForWakeOrTimeout(Math.min(maxIdleMs, Math.max(minIdleMs, 700)));
            }
        }
    }

    private void maybeLogMetrics() {
        long now = System.currentTimeMillis();
        if (now - lastMetricsLogAt < properties.getMetricsLogIntervalMs()) {
            return;
        }

        long dailyProcessed = dailyProcessedWindow.getAndSet(0);
        long monthlyProcessed = monthlyProcessedWindow.getAndSet(0);
        long elapsedMs = Math.max(1L, now - lastMetricsLogAt);
        double windowMinutes = elapsedMs / 60_000.0;
        long pendingDaily = recalcQueueService.countPendingDaily();
        long pendingMonthly = recalcQueueService.countPendingMonthly();
        long inProgressDaily = recalcQueueService.countInProgressDaily();
        long inProgressMonthly = recalcQueueService.countInProgressMonthly();
        long failedDaily = recalcQueueService.countFailedDaily();
        long failedMonthly = recalcQueueService.countFailedMonthly();
        long retryingDaily = recalcQueueService.countRetryingDaily();
        long retryingMonthly = recalcQueueService.countRetryingMonthly();
        long pendingAgeDailySeconds = recalcQueueService.pendingAgeSecondsDaily();
        long pendingAgeMonthlySeconds = recalcQueueService.pendingAgeSecondsMonthly();

        double dailyPerMinute = dailyProcessed / windowMinutes;
        double monthlyPerMinute = monthlyProcessed / windowMinutes;
        double dailyRetryRate = retryingDaily / (double) Math.max(1L, pendingDaily + inProgressDaily);
        double monthlyRetryRate = retryingMonthly / (double) Math.max(1L, pendingMonthly + inProgressMonthly);

        double avgDailyMs = resolveMeanJobDurationMs("daily");
        double avgMonthlyMs = resolveMeanJobDurationMs("monthly");

        log.info("Queue metrics: pendingDaily={} pendingMonthly={} pendingAgeDailySec={} pendingAgeMonthlySec={} inProgressDaily={} inProgressMonthly={} failedDaily={} failedMonthly={} dailyPerMin={} monthlyPerMin={} avgDailyMs={} avgMonthlyMs={} retryRateDaily={} retryRateMonthly={}",
                pendingDaily,
                pendingMonthly,
                pendingAgeDailySeconds,
                pendingAgeMonthlySeconds,
                inProgressDaily,
                inProgressMonthly,
                failedDaily,
                failedMonthly,
                String.format("%.2f", dailyPerMinute),
                String.format("%.2f", monthlyPerMinute),
                String.format("%.2f", avgDailyMs),
                String.format("%.2f", avgMonthlyMs),
                String.format("%.3f", dailyRetryRate),
                String.format("%.3f", monthlyRetryRate));

        maybeWarnBacklog(pendingDaily, pendingMonthly, pendingAgeDailySeconds, pendingAgeMonthlySeconds);
        lastMetricsLogAt = now;
    }

    private void maybeWarnBacklog(long pendingDaily,
                                  long pendingMonthly,
                                  long pendingAgeDailySeconds,
                                  long pendingAgeMonthlySeconds) {
        boolean backlogHigh = pendingDaily >= properties.getPendingWarnThresholdDaily()
                || pendingMonthly >= properties.getPendingWarnThresholdMonthly()
                || pendingAgeDailySeconds >= properties.getPendingLatencyWarnSecondsDaily()
                || pendingAgeMonthlySeconds >= properties.getPendingLatencyWarnSecondsMonthly();
        if (!backlogHigh) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBacklogWarnAt < Math.max(1000L, properties.getWarnCooldownMs())) {
            return;
        }

        lastBacklogWarnAt = now;
        log.warn("Queue backlog threshold exceeded: pendingDaily={} (threshold={}) pendingMonthly={} (threshold={}) pendingAgeDailySec={} (threshold={}) pendingAgeMonthlySec={} (threshold={})",
                pendingDaily,
                properties.getPendingWarnThresholdDaily(),
                pendingMonthly,
                properties.getPendingWarnThresholdMonthly(),
                pendingAgeDailySeconds,
                properties.getPendingLatencyWarnSecondsDaily(),
                pendingAgeMonthlySeconds,
                properties.getPendingLatencyWarnSecondsMonthly());
    }

    private double resolveMeanJobDurationMs(String type) {
        var timer = meterRegistry.find("recalc.job.duration").tag("type", type).timer();
        return timer == null ? 0.0 : timer.mean(TimeUnit.MILLISECONDS);
    }

    private void waitForWakeOrTimeout(long timeoutMs) {
        wakeSignal.await(timeoutMs);
    }
}