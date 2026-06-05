package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DailyReportWorker {

    private static final int BATCH_SIZE = 5;
    private static final String WORKER_ID = "daily-" + UUID.randomUUID();

    private final RecalcQueueService recalcQueueService;
    private final DailyRecalcService dailyRecalcService;

    /**
     * Polls for pending daily recalculation jobs every 2 seconds (after previous run finishes).
     * Uses FOR UPDATE SKIP LOCKED so multiple instances can run safely in parallel.
     */
    @Scheduled(fixedDelay = 2000)
    public void processBatch() {
        List<Long> jobIds = recalcQueueService.claimDailyJobIds(BATCH_SIZE, WORKER_ID);
        if (jobIds.isEmpty()) return;

        log.debug("Daily worker claiming {} jobs", jobIds.size());
        for (Long jobId : jobIds) {
            try {
                dailyRecalcService.processJob(jobId);
            } catch (Exception e) {
                log.error("Daily recalc job {} failed: {}", jobId, e.getMessage(), e);
                dailyRecalcService.markFailed(jobId, e.getMessage());
            }
        }
    }
}
