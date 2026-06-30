package com.aleksandarparipovic.marel_app.report_worker;

import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DailyReportWorker {

    private final RecalcQueueService recalcQueueService;
    private final DailyRecalcService dailyRecalcService;

    public int processBatch(int batchSize, String workerId, Duration loopBudget) {
        List<Long> jobIds = recalcQueueService.claimDailyJobIds(batchSize, workerId);
        if (jobIds.isEmpty()) {
            return 0;
        }

        long startedAt = System.nanoTime();
        int processed = 0;
        for (Long jobId : jobIds) {
            if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(loopBudget) > 0) {
                log.debug("Daily worker {} hit loop budget after {} jobs", workerId, processed);
                break;
            }

            long jobStartedAt = System.nanoTime();
            try {
                dailyRecalcService.processJob(jobId);
                processed++;
            } catch (Exception e) {
                log.error("Daily recalc job {} failed: {}", jobId, e.getMessage(), e);
                dailyRecalcService.markFailed(jobId, e.getMessage());
            } finally {
                long tookMs = Duration.ofNanos(System.nanoTime() - jobStartedAt).toMillis();
                log.debug("Daily job {} finished in {}ms", jobId, tookMs);
            }
        }
        return processed;
    }
}