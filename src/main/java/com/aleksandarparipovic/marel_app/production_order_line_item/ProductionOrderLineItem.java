package com.aleksandarparipovic.marel_app.production_order_line_item;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "production_order_line_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrderLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_id", nullable = false)
    private ProductionOrder productionOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Which variant this line orders (V78). NULL = a product without variants.
     * The composite FK proves the variant belongs to this line's product; the
     * service makes it REQUIRED once the product has live variants. A different
     * variant is a different line; the same variant may repeat freely.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_variant_id")
    private com.aleksandarparipovic.marel_app.product_variant.ProductVariant productVariant;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "product_description")
    private String productDescription;

    @Column(name = "line_order", nullable = false)
    private Integer lineOrder = 1;

    @Column(name = "note")
    private String note;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}
