package com.aleksandarparipovic.marel_app.notification;

import com.aleksandarparipovic.marel_app.report_worker.RecalcWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportNotificationService {

    private static final int MAX_DEDUP_KEYS = 10_000;

    private final SimpMessagingTemplate messagingTemplate;
    private final RecalcWorkerProperties properties;

    private final Map<String, Long> recentEventTs = new ConcurrentHashMap<>();

    public void sendDailyReportUpdate(Long employeeId, LocalDate workDate, Long workShiftId) {
        DailyReportEvent event = new DailyReportEvent(employeeId, workDate, workShiftId);
        String dedupKey = "daily:" + employeeId + ":" + workDate;
        publishAfterCommitWithDebounce(dedupKey, () -> {
            messagingTemplate.convertAndSend("/topic/reports/daily", event);
            log.debug("Sent daily report update WS event for emp={} shift={}", employeeId, workShiftId);
        });
    }

    public void sendMonthlyReportUpdate(Long employeeRecordId) {
        MonthlyReportEvent event = new MonthlyReportEvent(employeeRecordId);
        String dedupKey = "monthly:" + employeeRecordId;
        publishAfterCommitWithDebounce(dedupKey, () -> {
            messagingTemplate.convertAndSend("/topic/reports/monthly", event);
            log.debug("Sent monthly report update WS event for employeeRecordId={}", employeeRecordId);
        });
    }

    private void publishAfterCommitWithDebounce(String dedupKey, Runnable publisher) {
        Runnable guarded = () -> {
            long now = System.currentTimeMillis();
            long debounceMs = Math.max(1L, properties.getWebsocketDebounceMs());
            final boolean[] shouldPublish = {false};
            recentEventTs.compute(dedupKey, (key, previous) -> {
                if (previous == null || (now - previous) >= debounceMs) {
                    shouldPublish[0] = true;
                    return now;
                }
                return previous;
            });

            if (!shouldPublish[0]) {
                return;
            }

            cleanupDedupMap(now, debounceMs);
            publisher.run();
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
            return;
        }

        guarded.run();
    }

    private void cleanupDedupMap(long now, long debounceMs) {
        if (recentEventTs.size() <= MAX_DEDUP_KEYS) {
            return;
        }
        long cutoff = now - Math.max(2L * debounceMs, 5000L);
        recentEventTs.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
}