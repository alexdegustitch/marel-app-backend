package com.aleksandarparipovic.marel_app.production_order_scope_request;

import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One production-order line a scope request covers.
 *
 * <p>A request for a single line has exactly one of these; a request for the
 * whole order has one per active line. Storing both the same way is what lets
 * the completion modal, the response and the workflow have one shape instead of
 * two.
 */
@Entity
@Table(name = "production_order_scope_request_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrderScopeRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private ProductionOrderScopeRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_line_item_id", nullable = false, updatable = false)
    private ProductionOrderLineItem lineItem;

    /**
     * What the REQUESTER wrote about this line.
     *
     * <p>Prefilled from the line's own notes and then edited freely, so it is
     * stored here rather than read back from the line: the request has to keep
     * saying what was asked even after the order's notes are changed.
     */
    @Column(name = "note", length = 2000)
    private String note;

    /** The line's position in the order, copied so the answer reads in its order. */
    @Column(name = "line_order", nullable = false)
    @Builder.Default
    private Integer lineOrder = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    /**
     * The decided scope of this line. Empty until the processor saves; replaced
     * wholesale on every save, which is why it is orphan-removed.
     */
    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineOrder asc, id asc")
    @Builder.Default
    private List<ProductionOrderScopeRequestOperation> operations = new ArrayList<>();

    /** Throws away the previous answer for this line so a new one can be written. */
    public void replaceOperations(List<ProductionOrderScopeRequestOperation> replacements) {
        this.operations.clear();
        for (ProductionOrderScopeRequestOperation operation : replacements) {
            operation.setItem(this);
            this.operations.add(operation);
        }
    }
}
