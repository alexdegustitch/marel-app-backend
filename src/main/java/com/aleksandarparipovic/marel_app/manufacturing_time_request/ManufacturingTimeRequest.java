package com.aleksandarparipovic.marel_app.manufacturing_time_request;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.sample_order_line_item.SampleOrderLineItem;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A user's request to create, update, recalculate or deactivate a product's
 * manufacturing time.
 *
 * <p>Status only ever changes through the named methods below. There is
 * deliberately <b>no public status setter</b>, so no caller can force a state the
 * workflow forbids or the database check constraints would reject.
 */
@Entity
@Table(name = "manufacturing_time_requests")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManufacturingTimeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /** Who submitted it. Never settable by the client. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20, updatable = false)
    private ManufacturingTimeRequestType requestType;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ManufacturingTimeRequestStatus status = ManufacturingTimeRequestStatus.PENDING;

    /** Who currently owns the request. Set when it moves to IN_REVIEW. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    /** Who completed or declined it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by")
    private User processedBy;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "decision_note", length = 2000)
    private String decisionNote;

    /**
     * The manufacturing time that ANSWERS this request.
     *
     * <p>Many requests may share one record — two people can ask for the same
     * product's time and one record settles both — so the foreign key lives
     * here, on the side that has many. Set only when the request is completed,
     * whether the record was newly produced or an existing one was attached.
     *
     * <p>Not the same thing as {@code ProductManufacturingTime.sourceRequest},
     * which records which request last WROTE a record. Attaching an existing
     * record fills this field and deliberately leaves that one alone: attaching
     * is not authorship.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_manufacturing_time_id")
    private ProductManufacturingTime resultManufacturingTime;

    /** The record the request acts on. NULL for CREATE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_manufacturing_time_id", updatable = false)
    private ProductManufacturingTime targetManufacturingTime;

    /**
     * The production-order line the request was raised on. NULL means it was
     * raised on its own — the line is the occasion, never the subject: what the
     * request is about is always {@link #product}.
     *
     * <p>Not updatable, like the product: the occasion is a fact about how the
     * request came to exist. It keeps pointing at the line as it was even after
     * the order is edited, because editing an order deactivates its lines and
     * writes new ones rather than changing them in place.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_line_item_id", updatable = false)
    private ProductionOrderLineItem productionOrderLineItem;

    /**
     * The sample-order line the request was raised on. Same meaning, same
     * not-updatable reasoning as {@link #productionOrderLineItem}, and MUTUALLY
     * EXCLUSIVE with it: a request comes from one line or from none.
     *
     * <p>Samples are where somebody first notices that a product has no
     * manufacturing time — the piece is made once, by hand, and the question
     * "how long will this take in a run" is asked right there.
     *
     * <p>{@code chk_manufacturing_time_requests_single_occasion} enforces the
     * exclusion; {@link #occasionLineItemId()} is what reads it back without a
     * caller having to remember which of the two is set.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sample_order_line_item_id", updatable = false)
    private SampleOrderLineItem sampleOrderLineItem;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    /**
     * Stops two processors both completing or declining the same request: the
     * second flush fails with an OptimisticLockingFailureException (HTTP 409).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * The line this request was raised on, whichever kind of order it belongs
     * to, or null when it was raised on its own.
     *
     * <p>Exists so that "does this request have an occasion" is one question
     * rather than two — the database already guarantees at most one of the two
     * columns is set, and a caller that checks only the production one would
     * silently treat every sample-raised request as standalone.
     */
    public Long occasionLineItemId() {
        if (productionOrderLineItem != null) {
            return productionOrderLineItem.getId();
        }
        return sampleOrderLineItem == null ? null : sampleOrderLineItem.getId();
    }

    /**
     * Take ownership. Only an unowned, still-pending request can be claimed;
     * reassigning an owned request is a separate, permission-gated operation.
     */
    public void assignTo(User assignee) {
        require(ManufacturingTimeRequestStatus.IN_REVIEW);
        if (assignee == null) {
            throw new IllegalArgumentException("Assignee is required");
        }
        this.status = ManufacturingTimeRequestStatus.IN_REVIEW;
        this.assignedTo = assignee;
    }

    /** Hand an already-owned request to somebody else without changing status. */
    public void reassignTo(User assignee) {
        if (status != ManufacturingTimeRequestStatus.IN_REVIEW) {
            throw new ConflictException("Samo zahtev u obradi može da se prosledi drugom korisniku.");
        }
        if (assignee == null) {
            throw new IllegalArgumentException("Assignee is required");
        }
        this.assignedTo = assignee;
    }

    /** Give up ownership; the request returns to the open queue. */
    public void release() {
        if (status != ManufacturingTimeRequestStatus.IN_REVIEW) {
            throw new ConflictException("Samo zahtev u obradi može da se oslobodi.");
        }
        this.status = ManufacturingTimeRequestStatus.PENDING;
        this.assignedTo = null;
    }

    /**
     * Completes the request WITH its result, in one step.
     *
     * <p>The two move together because the database refuses to see them apart:
     * {@code chk_manufacturing_time_requests_result_state} makes COMPLETED and
     * "has a result" the same fact, so a flush between two separate setters
     * would be rejected.
     */
    public void complete(User processor, String note, ProductManufacturingTime result) {
        if (result == null) {
            throw new IllegalArgumentException("Zavrsen zahtev mora da ima vreme izrade.");
        }
        finish(ManufacturingTimeRequestStatus.COMPLETED, processor, note);
        this.resultManufacturingTime = result;
    }

    /**
     * Refuses an illegal completion BEFORE any result is produced.
     *
     * <p>{@link #complete} checks the same thing, but by then the result exists:
     * a CREATE would have written a manufacturing-time row that the rollback
     * then has to take back. Asking first keeps the refusal cheap and the log
     * clean.
     */
    public void requireCompletable() {
        require(ManufacturingTimeRequestStatus.COMPLETED);
    }

    public void decline(User processor, String note) {
        finish(ManufacturingTimeRequestStatus.DECLINED, processor, note);
    }

    public void cancel(String note) {
        require(ManufacturingTimeRequestStatus.CANCELLED);
        this.status = ManufacturingTimeRequestStatus.CANCELLED;
        this.cancelledAt = OffsetDateTime.now();
        // assigned_to is cleared so a cancelled request never looks like open work.
        this.assignedTo = null;
        this.decisionNote = normalize(note);
    }

    private void finish(ManufacturingTimeRequestStatus target, User processor, String note) {
        require(target);
        if (processor == null) {
            throw new IllegalArgumentException("Processor is required");
        }
        this.status = target;
        this.processedBy = processor;
        this.processedAt = OffsetDateTime.now();
        this.decisionNote = normalize(note);
    }

    private void require(ManufacturingTimeRequestStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException(
                    "Zahtev je u statusu " + status + " i ne može da pređe u " + target + "."
            );
        }
    }

    /** Blank becomes NULL — the database rejects a whitespace-only note. */
    private static String normalize(String note) {
        return (note == null || note.isBlank()) ? null : note.trim();
    }
}
