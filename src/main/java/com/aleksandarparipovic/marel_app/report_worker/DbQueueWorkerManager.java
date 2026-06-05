package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated background workers that continuously pull jobs from DB queue tables.
 * No in-memory task queue is used for business jobs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DbQueueWorkerManager {

    private static final int DAILY_THREADS = 2;
    private static final int MONTHLY_THREADS = 1;
    private static final int DAILY_BATCH = 5;
    private static final int MONTHLY_BATCH = 3;
    private static final int STUCK_RECOVERY_BATCH = 20;
    private static final Duration STUCK_TIMEOUT = Duration.ofMinutes(5);

    private final DailyReportWorker dailyReportWorker;
    private final MonthlyReportWorker monthlyReportWorker;
    private final RecalcQueueService recalcQueueService;

    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    @PostConstruct
    public void startWorkers() {
        for (int i = 1; i <= DAILY_THREADS; i++) {
            String workerId = "daily-worker-" + i;
            Thread thread = Thread.ofPlatform().name(workerId).start(() -> dailyLoop(workerId));
            threads.add(thread);
        }
        for (int i = 1; i <= MONTHLY_THREADS; i++) {
            String workerId = "monthly-worker-" + i;
            Thread thread = Thread.ofPlatform().name(workerId).start(() -> monthlyLoop(workerId));
            threads.add(thread);
        }
        log.info("Started DB queue workers: daily={} monthly={}", DAILY_THREADS, MONTHLY_THREADS);
    }

    @PreDestroy
    public void stopWorkers() {
        running = false;
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
        int idleMs = 200;
        int loop = 0;
        while (running) {
            try {
                if (loop++ % 20 == 0) {
                    recalcQueueService.requeueStuckDailyJobs(STUCK_TIMEOUT, STUCK_RECOVERY_BATCH);
                }
                int processed = dailyReportWorker.processBatch(DAILY_BATCH, workerId);
                if (processed == 0) {
                    sleepQuietly(idleMs);
                    idleMs = Math.min(2000, idleMs * 2);
                } else {
                    idleMs = 200;
                }
            } catch (Exception e) {
                log.error("Daily worker loop {} failed", workerId, e);
                sleepQuietly(500);
            }
        }
    }

    private void monthlyLoop(String workerId) {
        int idleMs = 300;
        int loop = 0;
        while (running) {
            try {
                if (loop++ % 20 == 0) {
                    recalcQueueService.requeueStuckMonthlyJobs(STUCK_TIMEOUT, STUCK_RECOVERY_BATCH);
                }
                int processed = monthlyReportWorker.processBatch(MONTHLY_BATCH, workerId);
                if (processed == 0) {
                    sleepQuietly(idleMs);
                    idleMs = Math.min(2500, idleMs * 2);
                } else {
                    idleMs = 300;
                }
            } catch (Exception e) {
                log.error("Monthly worker loop {} failed", workerId, e);
                sleepQuietly(700);
            }
        }
    }

    private void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

