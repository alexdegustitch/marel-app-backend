package com.aleksandarparipovic.marel_app.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public void sendDailyReportUpdate(Long employeeId, LocalDate workDate, Long workShiftId) {
        DailyReportEvent event = new DailyReportEvent(employeeId, workDate, workShiftId);
        messagingTemplate.convertAndSend("/topic/reports/daily", event);
        log.debug("Sent daily report update WS event for emp={} shift={}", employeeId, workShiftId);
    }

    public void sendMonthlyReportUpdate(Long employeeId, int year, int month) {
        MonthlyReportEvent event = new MonthlyReportEvent(employeeId, year, month);
        messagingTemplate.convertAndSend("/topic/reports/monthly", event);
        log.debug("Sent monthly report update WS event for emp={} {} / {}", employeeId, year, month);
    }
}
