package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_code")
    private String productCode;

    @Column(name = "description")
    private String description;

    /**
     * The catalogue type this product belongs to, when it belongs to one.
     * NULL means uncategorised/administration — a permanent, valid answer.
     * The type owns the spec schema this product's attribute values fill in.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id")
    private ProductType productType;

    /** The catalogue number (e.g. 390010). Unique among products that carry one. */
    @Column(name = "catalog_number", length = 50)
    private String catalogNumber;

    /** The discriminator inside the code (CBM 95 → 95). A label, not a hierarchy level. */
    @Column(name = "subtype", length = 50)
    private String subtype;

    /** Free-text person responsible for the product. Optional. */
    @Column(name = "supervisor_name")
    private String supervisorName;

    /** Optional shown-name override. When null the display name is productName [+ " " + subtype]. */
    @Column(name = "display_name")
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Operation> operations = new HashSet<>();

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

    public void archive() {
        this.active = false;
        this.archivedAt = OffsetDateTime.now();
    }

    public void reactivate() {
        this.active = true;
        this.archivedAt = null;
    }
}
