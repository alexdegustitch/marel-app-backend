package com.aleksandarparipovic.marel_app.order_reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface OrderReminderRepository extends JpaRepository<OrderReminder, Long> {

    boolean existsByOrderTypeAndOrderIdAndSubjectKeyAndDeadlineDateAndThresholdDays(
            String orderType, Long orderId, String subjectKey,
            LocalDate deadlineDate, Integer thresholdDays);
}
