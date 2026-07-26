package com.aleksandarparipovic.marel_app.production_order;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "production_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // User responsible for the order
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "creation_date")
    private LocalDate creationDate;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "delivery_deadline")
    private String deliveryDeadline;

    @Column(name = "testing_required", nullable = false)
    private Boolean testingRequired = false;

    @Column(name = "is_high_priority")
    private Boolean isHighPriority = false;

    @Column(name = "is_announced")
    private Boolean isAnnounced = false;

    @Column(name = "has_successive_deliveries")
    private Boolean hasSuccessiveDeliveries = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductionOrderStatus status = ProductionOrderStatus.CREATED;

    @Column(name = "note")
    private String note;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // DB managed timestamps
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}
