package com.aleksandarparipovic.marel_app.monthly_scrap;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Scrap that was never reported during the month, counted once at the end of it.
 *
 * <p>Not to be confused with {@code work_logs.scrap}, which is the scrap a worker
 * declares on one operation on one day. This is the remainder: what stocktaking
 * finds and nobody wrote down. One row per (month, product, operation), entered
 * by hand from the monthly records screen.
 *
 * <p><b>period is the FIRST day of the month</b> and the database insists on it
 * ({@code chk_monthly_scraps_period_month}). It is never taken from the client —
 * the service builds it from the year and month the screen is showing.
 *
 * <p><b>product is stored beside operation and cannot disagree with it.</b> The
 * operation already determines the product, so this is the same fact twice; a
 * composite foreign key on {@code (operation_id, product_id)} is what makes the
 * duplication safe. It is stored because this table is read weeks later, when a
 * re-pointed operation would silently rewrite history.
 */
@Entity
@Table(name = "monthly_scraps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyScrap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** First day of the month the count belongs to. */
    @Column(name = "period", nullable = false)
    private LocalDate period;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Optional: the order the scrap is attributed to, when it is known. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_id")
    private ProductionOrder productionOrder;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /*
     * Removal is a deactivation, not a DELETE. set_archived_at_on_deactivate
     * stamps archived_at the moment this flips to false, so a miscount that was
     * taken back is still readable — which is the whole point of counting scrap.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    // Written by the column default; never sent by the application.
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    // Written by trg_03_monthly_scraps_updated_at. Setting it here would be a
    // value the trigger immediately overwrites.
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
