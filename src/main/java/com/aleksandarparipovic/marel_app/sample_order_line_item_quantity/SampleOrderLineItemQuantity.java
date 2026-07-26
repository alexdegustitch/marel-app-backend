package com.aleksandarparipovic.marel_app.sample_order_line_item_quantity;

import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "sample_order_line_item_quantities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleOrderLineItemQuantity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sample_order_line_item_id", nullable = false)
    private SampleOrderLineItem sampleOrderLineItem;

    @Column(name = "order_quantity", nullable = false)
    private Integer orderQuantity = 1;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}
