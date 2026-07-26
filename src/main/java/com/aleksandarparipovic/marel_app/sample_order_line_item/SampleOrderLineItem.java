package com.aleksandarparipovic.marel_app.sample_order_line_item;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "sample_order_line_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sample_order_line_items_order_line",
                columnNames = {"sample_order_id", "order_line"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleOrderLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sample_order_id", nullable = false)
    private SampleOrder sampleOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "order_line")
    private Integer orderLine;

    // Unique per sample order when not null (uq_sample_order_line_items_catalog_number, DB-enforced partial index)
    @Column(name = "catalog_no")
    private String catalogNo;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

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
