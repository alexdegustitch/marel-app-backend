package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "operations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_operations_product_op_name_ci",
                        columnNames = {"product_id", "op_name"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Operation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL on a standalone operation — the two facts are tied by a DB check. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /**
     * An OSNOVNA operation: logged on kartoni but belonging to no product
     * (bolovanje, godišnji odmor, neplaćeno odsustvo, neradni dan). Carries no
     * norm and must carry a work code category — the DB checks say so too.
     */
    @Column(name = "is_standalone", nullable = false)
    private boolean standalone = false;

    // Optional default work code category for this operation (FK ON DELETE SET NULL).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_code_category_id")
    private WorkCodeCategory workCodeCategory;

    @Column(name = "op_name", nullable = false)
    private String opName;

    @Column(name = "description")
    private String description;

    @Column(name = "min_norm")
    private Integer minNorm;

    @Column(name = "max_norm")
    private Integer maxNorm;

    @Column(name = "norm_required", nullable = false)
    private boolean normRequired = true;

    @Column(name = "units_per_product")
    private Integer unitsPerProduct;

    @Column(name = "norm_date")
    private LocalDate normDate;

    @Column(name = "is_temporary", nullable = false)
    private boolean temporary = false;

    /**
     * The group of interchangeable machine-operations this one belongs to, or
     * NULL for the ordinary single operation. The composite FK in V77 makes a
     * member share the group's product; the service makes members share
     * binding, units_per_product and sort_order.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operation_group_id")
    private OperationGroup operationGroup;

    /** The member manufacturing time preselects and razrada names. One per group (radio). */
    @Column(name = "is_group_default", nullable = false)
    private boolean groupDefault = false;

    /** Step within the product: lower earlier, EQUAL = parallel, NULL = not yet placed. */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /**
     * The binding (V77): the attribute value this operation exists for. Empty =
     * applies to every variant of its product. The service keeps it to at most
     * one row (SPEC odluka 4).
     */
    @OneToMany(mappedBy = "operation", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<OperationVariantOption> variantBindings = new java.util.ArrayList<>();

    @Column(name = "archived_by_product", nullable = false)
    private boolean archivedByProduct = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /** Why the operation was archived, and by whom. Set together or not at all. */
    @Column(name = "archived_reason")
    private String archivedReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by")
    private com.aleksandarparipovic.marel_app.user.User archivedBy;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isValidNormRange() {
        return minNorm != null && maxNorm != null && minNorm <= maxNorm;
    }

    public boolean isNormValueValid() {
        return minNorm != null && maxNorm != null && minNorm > 0 && maxNorm > 0 && minNorm <= maxNorm;
    }

    public void archive() {
        this.active = false;
        this.archivedAt = OffsetDateTime.now();
    }

    public void reactivate() {
        this.active = true;
        this.archivedAt = null;
    }

    public void updateNorm(Integer minNorm, Integer maxNorm) {
        if (!Objects.equals(this.minNorm, minNorm)) {
            this.minNorm = minNorm;
        }
        if (!Objects.equals(this.maxNorm, maxNorm)) {
            this.maxNorm = maxNorm;
        }
    }
}
