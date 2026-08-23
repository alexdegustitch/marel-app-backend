package com.aleksandarparipovic.marel_app.product_manufacturing_time;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "product_manufacturing_times")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductManufacturingTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title")
    private String title;

    /** One note about the calculation as a whole. Prints above the table. */
    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "date_of_issue", nullable = false)
    private LocalDate dateOfIssue;

    @Column(name = "manufacturing_coefficient", precision = 10, scale = 6)
    private BigDecimal manufacturingCoefficient;

    @Column(name = "products_per_hour", precision = 10, scale = 4)
    private BigDecimal productsPerHour;

    @Column(name = "manufacturing_time_seconds")
    private Integer manufacturingTimeSeconds;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    /**
     * The request that most recently produced the current state of this record.
     *
     * <p>Cardinality is one-to-one and enforced by uq_pmt_source_request_id: a
     * request yields at most one manufacturing-time record. A CREATE request sets
     * it on a new row; UPDATE / RECALCULATE / DEACTIVATE re-stamp it on the row
     * they act on, so the earlier producing request is superseded here — the full
     * chain lives in audit_logs. NULL when the record was created directly rather
     * than through a request.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_request_id")
    private com.aleksandarparipovic.marel_app.manufacturing_time_request.ManufacturingTimeRequest sourceRequest;
}
