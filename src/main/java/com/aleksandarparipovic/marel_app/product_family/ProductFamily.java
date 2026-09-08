package com.aleksandarparipovic.marel_app.product_family;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * The top level of the catalogue hierarchy: family → type → product.
 *
 * <p>A family groups the kinds of thing the factory makes (for a cable-lug maker
 * that is "Kablovske papučice i čaure"; for a dairy it would be "Mleko"). It
 * carries a name, an optional description and its own images, but <b>no
 * behaviour</b> — norms, operations and payroll never look at it. It exists to
 * group and to present.
 *
 * <p><b>Deactivated, never deleted.</b> A family a product was filed under has to
 * survive the family falling out of use, so the row is archived, not removed.
 *
 * <p>The three timestamps are the DATABASE's — {@code archived_at} and
 * {@code updated_at} are written by triggers, so they are read here and never
 * inserted.
 */
@Entity
@Table(name = "product_families")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductFamily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // DB: NOT NULL + check length(btrim(name)) > 0, unique case-insensitively.
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /** Deliberate display order; the catalogue is not alphabetical. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;
}
