package com.aleksandarparipovic.marel_app.production_order_deadline;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "production_order_deadlines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrderDeadline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_id", nullable = false)
    private ProductionOrder productionOrder;

    @Column(name = "deadline_order", nullable = false)
    private Integer deadlineOrder = 1;

    @Column(name = "deadline_date_from")
    private LocalDate deadlineDateFrom;

    @Column(name = "deadline_date_to", nullable = false)
    private LocalDate deadlineDateTo;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}
