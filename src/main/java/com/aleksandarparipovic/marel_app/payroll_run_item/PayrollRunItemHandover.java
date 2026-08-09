package com.aleksandarparipovic.marel_app.payroll_run_item;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One step of the handover between the shop floor and payroll.
 *
 * <p>{@code SUBMITTED} is DRAFT → APPROVED — a supervisor saying the month is
 * done. {@code RETURNED} is APPROVED → DRAFT — it being sent back for
 * correction. Both are rows because the real workflow is a sequence, and two
 * columns on the item could only ever hold the last one.
 *
 * <p>The figures are the ones AT THAT MOMENT, not a live view: this system
 * recalculates aggressively, so by the time anybody asks what was handed over,
 * the item no longer says. There is no role dimension — the row states what was
 * true, and who may read it is decided when it is read.
 *
 * <p><b>Append-only, enforced by the database.</b> There is deliberately no
 * setter path back: a handover recorded by mistake is corrected by recording
 * the next one, never by editing this.
 */
@Entity
@Table(name = "payroll_run_item_handovers")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollRunItemHandover {

    public static final String EVENT_SUBMITTED = "SUBMITTED";
    public static final String EVENT_RETURNED = "RETURNED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payroll_run_item_id", nullable = false)
    private Long payrollRunItemId;

    @Column(name = "event", nullable = false)
    private String event;

    /** Null only if the user was removed later; the event still happened. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "status_before", nullable = false)
    private String statusBefore;

    @Column(name = "status_after", nullable = false)
    private String statusAfter;

    @Column(name = "total_net_earnings")
    private BigDecimal totalNetEarnings;

    @Column(name = "net_payable_amount")
    private BigDecimal netPayableAmount;

    /** Why it was sent back. Meaningful on RETURNED, optional on SUBMITTED. */
    @Column(name = "note")
    private String note;
}
