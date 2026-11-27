package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.product.Product;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Interchangeable operations of one product: the same job on different
 * machines, each member with its own norm and its own history.
 *
 * <p>One member is the default — what manufacturing time preselects and what
 * razrada names; progress counts the UNION of all active members through the
 * COALESCE(group, operation) key. The old machine is never archived: it runs
 * again whenever the new one stands.
 *
 * <p>The members must share product (composite FK), binding set,
 * units_per_product and sort_order — otherwise summing their quantities would
 * mean nothing. {@code OperationService} holds those lines.
 */
@Entity
@Table(name = "operation_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** "Presovanje" — unique per product among live groups, case-insensitively. */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "note")
    private String note;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void archive() {
        this.isActive = false;
        this.archivedAt = OffsetDateTime.now();
    }

    public void restore() {
        this.isActive = true;
        this.archivedAt = null;
    }
}
