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
public class MonthlyReportWorker {

    private final RecalcQueueService recalcQueueService;
    private final MonthlyRecalcService monthlyRecalcService;

    public int processBatch(int batchSize, String workerId, Duration loopBudget) {
        List<Long> jobIds = recalcQueueService.claimMonthlyJobIds(batchSize, workerId);
        if (jobIds.isEmpty()) {
            return 0;
        }

        long startedAt = System.nanoTime();
        int processed = 0;
        for (Long jobId : jobIds) {
            if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(loopBudget) > 0) {
                log.debug("Monthly worker {} hit loop budget after {} jobs", workerId, processed);
                break;
            }

            long jobStartedAt = System.nanoTime();
            try {
                monthlyRecalcService.processJob(jobId);
                processed++;
            } catch (Exception e) {
                log.error("Monthly recalc job {} failed: {}", jobId, e.getMessage(), e);
                monthlyRecalcService.markFailed(jobId, e.getMessage());
            } finally {
                long tookMs = Duration.ofNanos(System.nanoTime() - jobStartedAt).toMillis();
                log.debug("Monthly job {} finished in {}ms", jobId, tookMs);
            }
        }
        return processed;
    }
}