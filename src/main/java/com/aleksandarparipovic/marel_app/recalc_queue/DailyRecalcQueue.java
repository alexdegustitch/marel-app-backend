package com.aleksandarparipovic.marel_app.recalc_queue;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "daily_report_recalc_queue")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyRecalcQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "work_date")
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_shift_id", nullable = false)
    private WorkShift workShift;

    @Column(name = "reason")
    private String reason;

    @Column(name = "status")
    private String status;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "stuck_count")
    private Integer stuckCount;

    @Column(name = "last_stuck_at")
    private OffsetDateTime lastStuckAt;

    @Column(name = "version")
    private Integer version;
}
