package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "product_manufacturing_time_operations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductManufacturingTimeOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_manufacturing_time_id", nullable = false)
    private ProductManufacturingTime productManufacturingTime;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    @Column(name = "operation_name", nullable = false)
    private String operationName;

    // Units per product
    @Column(name = "units_per_product_snapshot")
    private Integer unitsPerProductSnapshot;

    @Column(name = "units_per_product_overridden", nullable = false)
    private Boolean unitsPerProductOverridden = false;

    @Column(name = "units_per_product_value")
    private Integer unitsPerProductValue;

    // Norm
    @Column(name = "norm_snapshot")
    private BigDecimal normSnapshot;

    @Column(name = "norm_overridden", nullable = false)
    private Boolean normOverridden = false;

    @Column(name = "norm_value")
    private BigDecimal normValue;

    // Norm date
    @Column(name = "norm_date_snapshot")
    private LocalDate normDateSnapshot;

    @Column(name = "norm_date_overridden", nullable = false)
    private Boolean normDateOverridden = false;

    @Column(name = "norm_date_value")
    private LocalDate normDateValue;

    /**
     * Why this line has no norm date: TEMPORARY (the operation's norm was
     * entered undated on purpose) or ANALYTICS (the norm was read out of the
     * recorded work). Null when there is a date, or when the record predates
     * this being written down.
     *
     * <p>It is stored because the record is a snapshot: a report rebuilt from it
     * has no operation to ask, and printing "–" would make the same document say
     * two different things depending on where it was generated.
     */
    @Column(name = "norm_date_note")
    private String normDateNote;

    /**
     * What somebody wrote about THIS line of THIS calculation — why it was left
     * out, where its norm came from. Snapshotted with everything else: it belongs
     * to the calculation, not to the operation, and must not follow the operation
     * when it is edited later.
     */
    @Column(name = "note")
    private String note;

    @Column(name = "excluded", nullable = false)
    private Boolean excluded = false;

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
}

