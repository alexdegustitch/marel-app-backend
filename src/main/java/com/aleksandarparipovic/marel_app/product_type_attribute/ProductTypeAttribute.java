package com.aleksandarparipovic.marel_app.product_type_attribute;

import com.aleksandarparipovic.marel_app.product_type.ProductType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One column of a type's spec schema: an attribute its products carry.
 *
 * <p>The catalogue's spec table is fixed per type — every CuCPST row has a, D,
 * D1, D2, crimpings, weight; every CBM row has d1, d2, L, L1. So the SCHEMA is a
 * property of the type and lives here; the numbers are a property of the product
 * and live in product_attribute_values.
 *
 * <p>Not soft-archived like families and types — {@code isActive} is a plain flag
 * that hides an attribute from the form without deleting the values already
 * recorded against it. It is never hard-deleted while any value references it
 * (the database refuses).
 */
@Entity
@Table(name = "product_type_attributes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductTypeAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_type_id", nullable = false)
    private ProductType productType;

    /** The attribute's key/label: "cross_section", "d1", "L", "weight". Unique per type, CI. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Unit of measure when it has one: "mm", "mm²", "kg/100", "kom". */
    @Column(name = "unit", length = 30)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    @Builder.Default
    private AttributeDataType dataType = AttributeDataType.TEXT;

    /** Column order as it appears in the catalogue. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean required = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;
}
