package com.aleksandarparipovic.marel_app.sample_order_line_item;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
/*
 * No uniqueConstraints here, deliberately.
 *
 * The rule is "one LIVE line per position", which JPA cannot express: it is a
 * PARTIAL unique index (uq_sample_order_line_items_order_line_active, on
 * sample_order_id and order_line WHERE is_active AND archived_at IS NULL). A
 * plain UNIQUE was declared here and in the database until V17, and it was
 * wrong — editing an order archives its lines and inserts a fresh set at the
 * same positions, so the second save of any order collided with its own history.
 */
@Table(name = "sample_order_line_items")
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

    /**
     * Where the line sits in the order, from 1. Unique among the order's LIVE
     * lines (uq_sample_order_line_items_order_line_active) — archived revisions
     * are history and keep whatever position they had.
     */
    @Column(name = "order_line")
    private Integer orderLine;

    // Unique per sample order when not null (uq_sample_order_line_items_catalog_number, DB-enforced partial index)
    @Column(name = "catalog_no")
    private String catalogNo;

    /**
     * How many. ONE number, unlike a production order's list of dated quantity
     * rows: samples are made in a single run, so a second row with its own rok
     * would describe a delivery that does not happen. It can still be changed
     * afterwards, and each change is kept as a revision in
     * sample_order_line_item_quantities.
     *
     * <p>The database requires it to be positive
     * ({@code chk_sample_order_line_items_quantity_valid}).
     */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /**
     * What to make out of this product for this order — the "opis za radnike".
     * Distinct from {@link #note}, which is a remark ABOUT the line: the
     * description is the instruction, and the note is what somebody adds beside
     * it.
     */
    @Column(name = "product_description")
    private String productDescription;

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
