package com.aleksandarparipovic.marel_app.order_reminder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The morning sweep over every open order: deadline reminders and the
 * "all line items are done" notice.
 *
 * <p>09:00 Europe/Belgrade — the agreed hour: late enough that the office is
 * reading mail, early enough to act on "ističe danas". The zone convention
 * follows {@code DashboardInsightJob} (05:15) and
 * {@code EmployeeDeactivationScheduler} (06:30).
 *
 * <p>Each order is its own transaction inside the service, and its own
 * try/catch here — one order with broken data must not cost every other
 * order its reminder. What was already sent is recorded in
 * {@code order_reminders}, so a rerun after a crash resends nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReminderJob {

    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");

    private final OrderReminderService reminderService;

    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Belgrade")
    public void runDaily() {
        LocalDate today = LocalDate.now(ZONE);
        int failures = 0;

        for (Long orderId : reminderService.openProductionOrderIds()) {
            try {
                reminderService.remindProductionOrder(orderId, today);
                reminderService.notifyProductionOrderComplete(orderId);
            } catch (RuntimeException ex) {
                failures++;
                log.error("[OrderReminder] Nalog {} nije obrađen", orderId, ex);
            }
        }

        for (Long orderId : reminderService.openSampleOrderIds()) {
            try {
                reminderService.remindSampleOrder(orderId, today);
            } catch (RuntimeException ex) {
                failures++;
                log.error("[OrderReminder] Nalog za uzorke {} nije obrađen", orderId, ex);
            }
        }

        if (failures > 0) {
            log.warn("[OrderReminder] Jutarnja provera završena uz {} grešaka.", failures);
        }
    }
}
