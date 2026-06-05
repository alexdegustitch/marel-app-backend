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
public class MonthlyReportWorker {

    private static final int BATCH_SIZE = 3;
    private static final String WORKER_ID = "monthly-" + UUID.randomUUID();

    private final RecalcQueueService recalcQueueService;
    private final MonthlyRecalcService monthlyRecalcService;

    /**
     * Polls for pending monthly recalculation jobs every 5 seconds.
     */
    @Scheduled(fixedDelay = 5000)
    public void processBatch() {
        List<Long> jobIds = recalcQueueService.claimMonthlyJobIds(BATCH_SIZE, WORKER_ID);
        if (jobIds.isEmpty()) return;

        log.debug("Monthly worker claiming {} jobs", jobIds.size());
        for (Long jobId : jobIds) {
            try {
                monthlyRecalcService.processJob(jobId);
            } catch (Exception e) {
                log.error("Monthly recalc job {} failed: {}", jobId, e.getMessage(), e);
                monthlyRecalcService.markFailed(jobId, e.getMessage());
            }
        }
    }
}
