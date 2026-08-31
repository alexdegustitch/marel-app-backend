package com.aleksandarparipovic.marel_app.payroll_run_item;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

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
/*
 * Immutable to Hibernate as well as to the database.
 *
 * Without this, dirty checking issues an UPDATE for this row at the next flush
 * in the same transaction — the jsonb Map cannot be compared cheaply, so it is
 * treated as changed every time — and the append-only trigger rejects it. The
 * trigger was doing its job; the mapping was the one making a promise it should
 * never have made.
 */
@Immutable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollRunItemHandover {

    public static final String EVENT_SUBMITTED = "SUBMITTED";
    public static final String EVENT_RETURNED = "RETURNED";

    /**
     * The month was finished and frozen, and reopened.
     *
     * <p>They belong in the same chain as the handover, not in a log of their
     * own: "what happened to this payroll" is one question, and locking is its
     * last answer. The chain also stops making sense without them — it would
     * end at "predato" on a month that has been paid.
     */
    public static final String EVENT_LOCKED = "LOCKED";
    public static final String EVENT_UNLOCKED = "UNLOCKED";

    /**
     * A supervisor asking a finished month back, and what payroll answered.
     *
     * <p>In this chain rather than only on the request row, because the timeline
     * beside the payroll is where somebody looks to find out why a month that
     * was finished is open again.
     */
    public static final String EVENT_CHANGE_REQUESTED = "CHANGE_REQUESTED";
    public static final String EVENT_CHANGE_ACCEPTED = "CHANGE_ACCEPTED";
    public static final String EVENT_CHANGE_DECLINED = "CHANGE_DECLINED";


    /**
     * The payroll came into being. Never stored — {@code getHandovers} derives it
     * from the item's own created_at, because creating a payroll is not a step
     * somebody took in the handover chain and the fact is already recorded.
     */
    public static final String EVENT_CREATED = "CREATED";

    /**
     * The steps a supervisor is shown.
     *
     * <p>They see their own workflow — the handover, and the requests they
     * raised — and not what payroll does with the month afterwards. Freezing it
     * and reopening it are payroll's own record; a supervisor reading "otključan
     * / završen / otključan" is reading somebody else's working notes.
     *
     * <p>A WHITELIST rather than a list of what to hide: an event added later is
     * then invisible until somebody decides it should be seen, which is the safe
     * direction for a timeline about somebody's pay.
     */
    public static final java.util.Set<String> EVENTS_VISIBLE_TO_SUBMITTER = java.util.Set.of(
            EVENT_CREATED,
            EVENT_SUBMITTED,
            EVENT_RETURNED,
            EVENT_CHANGE_REQUESTED,
            EVENT_CHANGE_ACCEPTED,
            EVENT_CHANGE_DECLINED);


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

    /**
     * The lines as they stood: {@code {"lines":[{"c":code,"a":amount,...}]}}.
     *
     * <p>Payroll's question is not only "was the total different" but "which
     * line moved after I submitted", and two totals cannot answer that. Short
     * keys because jsonb repeats them in every row.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = Map.of();

    /** Why it was sent back. Meaningful on RETURNED, optional on SUBMITTED. */
    @Column(name = "note")
    private String note;
}
