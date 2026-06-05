package com.aleksandarparipovic.marel_app.report_worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Coordinates event-driven recalculation workflow.
 * 
 * Flow:
 * 1. Work log mutation triggers daily recalculation
 * 2. Daily worker processes immediately
 * 3. Monthly job is enqueued
 * 4. Monthly worker processes immediately
 * 5. WebSocket events notify frontend
 * 
 * All operations use version checks to ensure correctness under concurrent updates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecalcCoordinator {

    private final DailyReportWorker dailyReportWorker;
    private final MonthlyReportWorker monthlyReportWorker;

    /**
     * Trigger daily recalculation processing immediately after work log mutation.
     * Processes all pending daily jobs synchronously in a single transaction.
     */
    public void processDailyRecalculations() {
        log.debug("Starting event-driven daily recalculation processing");
        try {
            dailyReportWorker.processPendingJobs();
        } catch (Exception e) {
            log.error("Error during daily recalculation processing", e);
        }
    }

    /**
     * Trigger monthly recalculation processing after daily completes.
     * Processes all pending monthly jobs synchronously in a single transaction.
     */
    public void processMonthlyRecalculations() {
        log.debug("Starting event-driven monthly recalculation processing");
        try {
            monthlyReportWorker.processPendingJobs();
        } catch (Exception e) {
            log.error("Error during monthly recalculation processing", e);
        }
    }
}

