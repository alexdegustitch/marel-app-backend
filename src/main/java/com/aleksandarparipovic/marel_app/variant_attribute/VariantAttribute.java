package com.aleksandarparipovic.marel_app.variant_attribute;

import com.aleksandarparipovic.marel_app.product_type.ProductType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * What products of one type vary by in the WAY they are made: "Šraf", "Navrtka".
 *
 * <p>Deliberately NOT a {@code product_type_attribute} and not a flag on one.
 * Those describe how a product looks on paper (cross-section, weight); these
 * define the combinations a product exists in, and operations bind to their
 * values. The owner's words: one set describes the LOOK, this one the WAY
 * (docs/specs/varijante-i-grupe-operacija-SPEC.md, odluka 1).
 *
 * <p>Soft-archived like families and types: {@code archive()} keeps every
 * variant and binding that references it meaningful, it only leaves the forms.
 */
@Entity
@Table(name = "variant_attributes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VariantAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_type_id", nullable = false)
    private ProductType productType;

    /** "Šraf", "Navrtka" — unique per type, case-insensitively, among live ones. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** true = every variant must pick a value for it; false = a variant may leave it out. */
    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean required = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

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
