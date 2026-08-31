package com.aleksandarparipovic.marel_app.production_order_scope_request;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A request for the floor to say which operations a production order actually
 * needs, and how many of each go into one assembly.
 *
 * <p>That answer is what "how much of this order is done" is measured against:
 * without it the work recorded against an order can be counted but not compared
 * to anything.
 *
 * <p>Status only ever changes through the named methods below. There is
 * deliberately <b>no public status setter</b> — the same rule as
 * {@code ManufacturingTimeRequest}, and for the same reason: no caller can force
 * a state the workflow forbids or the database check constraints would reject.
 */
@Entity
@Table(name = "production_order_scope_requests")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductionOrderScopeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The order the request is about. Never changes. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_id", nullable = false, updatable = false)
    private ProductionOrder productionOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20, updatable = false)
    private ProductionOrderScopeRequestScope scope;

    /** Who submitted it. Never settable by the client. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ProductionOrderScopeRequestStatus status = ProductionOrderScopeRequestStatus.PENDING;

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

    /** Null until the processor saves anything. See {@link ProductionOrderScopeResultState}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "result_state", length = 20)
    private ProductionOrderScopeResultState resultState;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    /**
     * Stops two processors both submitting or declining the same request: the
     * second flush fails with an OptimisticLockingFailureException (HTTP 409).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * The order lines this request covers, in the order the order lists them.
     *
     * <p>Cascaded and orphan-removed because an item is PART OF the request, not
     * a thing that points at one — the same relationship the schema states with
     * {@code ON DELETE CASCADE}.
     */
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineOrder asc, id asc")
    @Builder.Default
    private List<ProductionOrderScopeRequestItem> items = new ArrayList<>();

    public void addItem(ProductionOrderScopeRequestItem item) {
        item.setRequest(this);
        this.items.add(item);
    }

    /**
     * Take ownership. Only an unowned, still-pending request can be claimed;
     * reassigning an owned request is a separate operation.
     */
    public void assignTo(User assignee) {
        require(ProductionOrderScopeRequestStatus.IN_REVIEW);
        if (assignee == null) {
            throw new IllegalArgumentException("Assignee is required");
        }
        this.status = ProductionOrderScopeRequestStatus.IN_REVIEW;
        this.assignedTo = assignee;
    }

    /** Hand an already-owned request to somebody else without changing status. */
    public void reassignTo(User assignee) {
        if (status != ProductionOrderScopeRequestStatus.IN_REVIEW) {
            throw new ConflictException("Samo zahtev u obradi može da se prosledi drugom korisniku.");
        }
        if (assignee == null) {
            throw new IllegalArgumentException("Assignee is required");
        }
        this.assignedTo = assignee;
    }

    /** Give up ownership; the request returns to the open queue. */
    public void release() {
        if (status != ProductionOrderScopeRequestStatus.IN_REVIEW) {
            throw new ConflictException("Samo zahtev u obradi može da se oslobodi.");
        }
        this.status = ProductionOrderScopeRequestStatus.PENDING;
        this.assignedTo = null;
    }

    /**
     * Saves the answer without handing it over.
     *
     * <p>The request stays IN_REVIEW and the processor keeps it: a scope that
     * takes two sittings to work out must not have to be finished in one, and a
     * half-finished scope must not be readable as the order's agreed answer.
     */
    public void saveDraft() {
        requireEditableResult();
        this.resultState = ProductionOrderScopeResultState.DRAFT;
    }

    /**
     * Hands the answer over, completing the request.
     *
     * <p>Completion and submission move together because the database refuses to
     * see them apart: {@code chk_po_scope_requests_result_state} makes COMPLETED
     * and SUBMITTED the same fact, so a flush between two separate setters would
     * be rejected.
     */
    public void submit(User processor, String note) {
        requireEditableResult();
        finish(ProductionOrderScopeRequestStatus.COMPLETED, processor, note);
        this.resultState = ProductionOrderScopeResultState.SUBMITTED;
    }

    /**
     * Whether the answer may still be written to.
     *
     * <p>Public because the service asks it BEFORE it starts replacing operation
     * rows: discovering the refusal afterwards would mean a rollback of writes
     * that should never have been attempted.
     */
    public void requireEditableResult() {
        if (status != ProductionOrderScopeRequestStatus.IN_REVIEW) {
            throw new ConflictException(
                    "Razrada može da se menja samo dok je zahtev u obradi.");
        }
        if (resultState == ProductionOrderScopeResultState.SUBMITTED) {
            throw new ConflictException("Razrada je već predata i više ne može da se menja.");
        }
    }

    public void decline(User processor, String note) {
        finish(ProductionOrderScopeRequestStatus.DECLINED, processor, note);
    }

    public void cancel(String note) {
        require(ProductionOrderScopeRequestStatus.CANCELLED);
        this.status = ProductionOrderScopeRequestStatus.CANCELLED;
        this.cancelledAt = OffsetDateTime.now();
        // assigned_to is cleared so a withdrawn request never looks like open work.
        this.assignedTo = null;
        this.decisionNote = normalize(note);
    }

    private void finish(ProductionOrderScopeRequestStatus target, User processor, String note) {
        require(target);
        if (processor == null) {
            throw new IllegalArgumentException("Processor is required");
        }
        this.status = target;
        this.processedBy = processor;
        this.processedAt = OffsetDateTime.now();
        this.decisionNote = normalize(note);
    }

    private void require(ProductionOrderScopeRequestStatus target) {
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
