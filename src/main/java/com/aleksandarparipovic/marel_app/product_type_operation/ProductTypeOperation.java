package com.aleksandarparipovic.marel_app.product_type_operation;

import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One operation in a product type's TEMPLATE — a blueprint, never a live operation.
 *
 * <p>When a product of this type is created the user MAY copy these onto it. Each
 * becomes an ordinary row in {@code operations}, from that moment fully independent
 * (a snapshot). This table is read only to author the template and to copy from it;
 * norms, payroll, work orders and analytics read {@code operations} and never this.
 *
 * <p>So it carries only the DEFINITION of an operation and a SUGGESTED norm — no
 * norm date and no version history. The real, dated, verifiable norm is entered on
 * the product's operation when the template is copied, exactly as the product-to-
 * product copy already records it.
 */
@Entity
@Table(
        name = "product_type_operations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_product_type_operations_type_op_name_ci",
                        columnNames = {"product_type_id", "op_name"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductTypeOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_type_id", nullable = false)
    private ProductType productType;

    /** The operation name a copy will carry. Unique per type, case-insensitively. */
    @Column(name = "op_name", nullable = false, length = 255)
    private String opName;

    @Column(name = "description")
    private String description;

    /** Suggested norm range and units. Optional — the real dated norm is per product. */
    @Column(name = "default_min_norm")
    private Integer defaultMinNorm;

    @Column(name = "default_max_norm")
    private Integer defaultMaxNorm;

    @Column(name = "default_units_per_product")
    private Integer defaultUnitsPerProduct;

    /** Default work-code category a copied operation starts with. FK ON DELETE SET NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_code_category_id")
    private WorkCodeCategory workCodeCategory;

    @Column(name = "norm_required", nullable = false)
    @Builder.Default
    private Boolean normRequired = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;
}
