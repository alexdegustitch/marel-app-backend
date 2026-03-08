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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operation_id")
    private Operation operation;

    // Production order
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_id")
    private ProductionOrder productionOrder;

    // Work code category
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_code_id")
    private WorkCodeCategory workCode;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    // Generated column
    @Column(name = "duration_min", insertable = false, updatable = false)
    private Integer durationMin;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 0;

    @Column(name = "scrap", nullable = false)
    private Integer scrap = 0;

    @Column(name = "comment")
    private String comment;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // Generated column
    @Column(name = "hourly_output", insertable = false, updatable = false)
    private BigDecimal hourlyOutput;

    // DB-managed timestamps
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}

