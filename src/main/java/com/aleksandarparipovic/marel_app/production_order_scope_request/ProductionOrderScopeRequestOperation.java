package com.aleksandarparipovic.marel_app.production_order_scope_request;

import com.aleksandarparipovic.marel_app.operation.Operation;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One operation of the product on a covered line, as the processor decided it.
 *
 * <p>A row exists for every operation the product had when the modal was filled,
 * including the ones marked not needed: the answer records the DECISION, not an
 * absence somebody later has to interpret.
 *
 * <p>{@code operationName} and {@code unitsPerProductSnapshot} are snapshots for
 * the same reason {@code product_manufacturing_time_operations} keeps its own —
 * renaming an operation or changing its catalogue quantity afterwards must not
 * silently rewrite what an order agreed to.
 */
@Entity
@Table(name = "production_order_scope_request_operations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrderScopeRequestOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_item_id", nullable = false)
    private ProductionOrderScopeRequestItem item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false, updatable = false)
    private Operation operation;

    @Column(name = "operation_name", nullable = false)
    private String operationName;

    /** FALSE is the processor saying "this variant does not need it". */
    @Column(name = "needed", nullable = false)
    @Builder.Default
    private Boolean needed = true;

    /** What the catalogue said when the modal was filled. */
    @Column(name = "units_per_product_snapshot")
    private Integer unitsPerProductSnapshot;

    /**
     * How many of this operation go into one assembly, as decided. Required
     * whenever the operation is needed; the database says so too.
     */
    @Column(name = "units_per_product_value")
    private Integer unitsPerProductValue;

    @Column(name = "line_order", nullable = false)
    @Builder.Default
    private Integer lineOrder = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
