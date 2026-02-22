package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.product.Product;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "op_name", nullable = false)
    private String opName;

    @Column(name = "description")
    private String description;

    @Column(name = "min_norm")
    private Integer minNorm;

    @Column(name = "max_norm")
    private Integer maxNorm;

    @Column(name = "units_per_product")
    private Integer unitsPerProduct;

    @Column(name = "norm_date")
    private LocalDate normDate;

    @Column(name = "is_temporary", nullable = false)
    private boolean temporary = false;

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
