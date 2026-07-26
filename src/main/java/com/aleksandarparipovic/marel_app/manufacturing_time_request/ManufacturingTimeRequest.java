package com.aleksandarparipovic.marel_app.manufacturing_time_request;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
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

    /** The record the request acts on. NULL for CREATE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_manufacturing_time_id", updatable = false)
    private ProductManufacturingTime targetManufacturingTime;

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

    public void complete(User processor, String note) {
        finish(ManufacturingTimeRequestStatus.COMPLETED, processor, note);
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
