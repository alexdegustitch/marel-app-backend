package com.aleksandarparipovic.marel_app.production_order_line_item_note;

import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "production_order_line_item_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrderLineItemNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_line_item_id", nullable = false)
    private ProductionOrderLineItem productionOrderLineItem;

    @Column(name = "order_note", nullable = false)
    private Integer orderNote = 1;

    @Column(name = "note", nullable = false)
    private String note = "";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}
