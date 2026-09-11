package com.aleksandarparipovic.marel_app.order_reminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One deadline warning the morning job has already sent.
 *
 * <p>The row IS the "already said so" memory: the job may crash, restart or be
 * rerun by hand, and the unique index over (order, subject, date, threshold)
 * is what keeps every rerun silent. A moved deadline carries a new date and
 * therefore warns again — deliberately, because the new date is a new promise.
 */
@Entity
@Table(name = "order_reminders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderReminder {

    /** Values of {@link #orderType} — which table {@link #orderId} points into. */
    public static final String PRODUCTION = "PRODUCTION";
    public static final String SAMPLE = "SAMPLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_type", nullable = false, length = 20)
    private String orderType;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * Which deadline warned: {@code ORDER} (the order's own rok), {@code
     * DEADLINE} (a successive delivery line), {@code ITEM:<product id>} (a
     * line item's partial quantity). Two subjects due on the same date are
     * two obligations and warn separately.
     */
    @Column(name = "subject_key", nullable = false, length = 120)
    private String subjectKey;

    @Column(name = "deadline_date", nullable = false)
    private LocalDate deadlineDate;

    // smallint in the schema — three possible values do not need four bytes.
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "threshold_days", nullable = false)
    private Integer thresholdDays;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;
}
