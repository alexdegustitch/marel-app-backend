package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "work_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Parent shift
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_shift_id", nullable = false)
    private WorkShift workShift;

    // Operation performed
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    // Production order
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_id")
    private ProductionOrder productionOrder;

    // Work code category as entered by the user (original; never overwritten by recalc).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_code_category_id", nullable = false)
    private WorkCodeCategory workCode;

    // Bonus-effective category set by recalc when a night/weekend remap applies.
    // NULL = no active remap (use workCode). Recomputed every recalc, so it reverts
    // automatically when the bonus condition no longer holds.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "effective_work_code_category_id")
    private WorkCodeCategory effectiveWorkCode;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    // Generated column
    @Column(name = "duration_min", insertable = false, updatable = false)
    private Integer durationMin;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "scrap")
    private Integer scrap;

    @Column(name = "note")
    private String note;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // Generated column
    @Column(name = "hourly_output", insertable = false, updatable = false)
    private BigDecimal hourlyOutput;

    @Column(name = "norm_multiplier_snapshot", precision = 38, scale = 2)
    private BigDecimal normMultiplierSnapshot;

    @Column(name = "performance_rate", precision = 38, scale = 2)
    private BigDecimal performanceRate;

    @Column(name = "approved_performance_rate", precision = 38, scale = 2)
    private BigDecimal approvedPerformanceRate;

    @Column(name = "paid_minutes", precision = 38, scale = 2)
    private BigDecimal paidMinutes;

    // DB-managed timestamps
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}

