package com.aleksandarparipovic.marel_app.product_type;

import com.aleksandarparipovic.marel_app.product_family.ProductFamily;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * The middle level of the catalogue: a kind of product within a family.
 *
 * <p>A type is named by a {@code code} the catalogue uses — "CuCPST", "CBM" —
 * unique within its family (the same code may recur under a different family).
 * It owns the spec schema its products fill in (see product_type_attributes),
 * but carries no behaviour of its own.
 *
 * <p><b>Deactivated, never deleted.</b> Products point at the type they are,
 * and that has to survive the type falling out of use.
 *
 * <p>The three timestamps are the DATABASE's, written by triggers — read here,
 * never inserted.
 */
@Entity
@Table(name = "product_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false)
    private ProductFamily family;

    // DB: NOT NULL + check length(btrim(name)) > 0
    @Column(name = "name", nullable = false)
    private String name;

    /** The code that names the type in the catalogue; unique per family, case-insensitively. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "description")
    private String description;

    @Column(name = "note")
    private String note;

    /** The standard(s) the type is made and tested to (e.g. "SRPS N.F4.101 | EN 61238-1-1"); free text. */
    @Column(name = "standard")
    private String standard;

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
