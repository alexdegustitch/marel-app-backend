package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecalcQueueCleanupJob {

    private static final int MAX_BATCH_ROUNDS_PER_RUN = 10;

    private final RecalcQueueService recalcQueueService;
    private final RecalcWorkerProperties properties;

    @Scheduled(
            fixedDelayString = "${app.recalc.cleanup-interval-ms:60000}",
            initialDelayString = "${app.recalc.cleanup-initial-delay-ms:30000}"
    )
    public void cleanupDoneJobs() {
        if (!properties.isCleanupEnabled()) {
            return;
        }

        int totalDaily = 0;
        int totalMonthly = 0;
        int batchSize = Math.max(1, properties.getCleanupBatchSize());
        long retentionDays = Math.max(1L, properties.getDoneRetentionDays());

        for (int i = 0; i < MAX_BATCH_ROUNDS_PER_RUN; i++) {
            int daily = recalcQueueService.cleanupDoneDailyJobs(retentionDays, batchSize);
            int monthly = recalcQueueService.cleanupDoneMonthlyJobs(retentionDays, batchSize);
            totalDaily += daily;
            totalMonthly += monthly;

            if (daily < batchSize && monthly < batchSize) {
                break;
            }
        }

        if (totalDaily > 0 || totalMonthly > 0) {
            log.info("Queue cleanup removed DONE jobs: daily={} monthly={} retentionDays={}",
                    totalDaily, totalMonthly, retentionDays);
        }
    }
}

