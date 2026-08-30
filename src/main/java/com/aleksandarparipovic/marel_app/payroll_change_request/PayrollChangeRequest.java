package com.aleksandarparipovic.marel_app.payroll_change_request;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A supervisor asking payroll to reopen a month they have already handed over.
 *
 * <p><b>Why this exists at all.</b> A submitted payroll used to be pullable back
 * by whoever held PAYROLL_HANDOVER — the supervisor. That is the wrong way round
 * once payroll has started working on it: the shop floor said the month was
 * finished, payroll began from that, and taking it back underneath them silently
 * invalidates whatever they have done since. So the direction reverses. The
 * supervisor asks, with a reason; payroll answers; nothing moves until it does.
 */
@Entity
@Table(name = "payroll_change_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_item_id", nullable = false, updatable = false)
    private PayrollRunItem payrollRunItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false, updatable = false)
    private User requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime requestedAt;

    /**
     * Why the month has to come back.
     *
     * <p>Compulsory, and the reason this is a request rather than a button:
     * "the month is wrong" is not something payroll can act on.
     */
    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayrollChangeRequestStatus status = PayrollChangeRequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    public boolean isPending() {
        return status == PayrollChangeRequestStatus.PENDING;
    }

    /**
     * Answers the request, in one step.
     *
     * <p>The status, the decider and the moment move together because the
     * database refuses to see them apart — chk_pcr_decision_state makes
     * "decided" and "has a decider" the same fact, so a flush between two
     * separate setters would be rejected.
     */
    public void decide(PayrollChangeRequestStatus outcome, User decider, String note) {
        if (status != PayrollChangeRequestStatus.PENDING) {
            throw new IllegalStateException("Request " + id + " is already " + status);
        }
        this.status = outcome;
        this.decidedBy = decider;
        this.decidedAt = OffsetDateTime.now();
        this.decisionNote = (note == null || note.isBlank()) ? null : note.trim();
    }
}
