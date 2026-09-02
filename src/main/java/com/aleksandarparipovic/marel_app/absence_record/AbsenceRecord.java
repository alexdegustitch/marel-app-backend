package com.aleksandarparipovic.marel_app.absence_record;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Time inside a shift the employee was not there for.
 *
 * <p>A shift from 06:00 to 14:00 that was worked until 12:00 leaves one of these
 * from 12:00 to 14:00. A full no-show leaves one across the whole shift — the
 * shift is still created, so that an absence always has one to hang from.
 *
 * <p><b>Not a work log.</b> {@code work_logs.operation_id} is NOT NULL, so an
 * absence entered there would need an invented operation, and would then be
 * weighed by the interval engine and the performance coefficients as though it
 * were work.
 *
 * <p>{@link #outcome} and {@link #compensatedMinutes} are NOT entered. They are
 * rewritten by the monthly allocation whenever the overtime bank moves, and are
 * the only thing in this row that can change after somebody records the absence.
 */
@Entity
@Table(name = "absence_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbsenceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_shift_id", nullable = false)
    private WorkShift workShift;

    /**
     * WHICH absence this is: NO, GO, PLO, SO. Only NO takes part in the overtime
     * bank; the rest are paid and are recorded here for the day's totals alone.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_code_category_id", nullable = false)
    private WorkCodeCategory workCodeCategory;

    @Column(name = "start_at")
    private OffsetDateTime startAt;

    @Column(name = "end_at")
    private OffsetDateTime endAt;

    @Column(name = "absence_minutes", nullable = false)
    private Integer absenceMinutes;

    /**
     * What the category was worth on the day this was recorded, so a later
     * re-versioning of the category cannot move a number somebody already saw.
     */
    @Column(name = "norm_multiplier_snapshot", nullable = false)
    private BigDecimal normMultiplierSnapshot;

    @Column(name = "paid_minutes", nullable = false)
    @Builder.Default
    private Integer paidMinutes = 0;

    /**
     * How much of this absence the overtime bank reached. Rewritten by the
     * allocation; never entered.
     *
     * <p>Being covered does NOT make the time paid — {@link #paidMinutes} stays
     * where it was. The bank buys back the day's standing, not its wage.
     */
    @Column(name = "compensated_minutes", nullable = false)
    @Builder.Default
    private Integer compensatedMinutes = 0;

    /** NULL for every absence except NO. See {@link AbsenceOutcome}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private AbsenceOutcome outcome;

    /**
     * ND when a person entered this day AS a neradni dan, rather than the
     * allocation deciding it was one.
     *
     * <p>Deliberately not a second value in {@link #outcome}: ND there has
     * always meant "the bank covered the whole shift", and an unfulfilled
     * request would make it mean two things at once. Read as a pair instead —
     * requested ND with outcome NO is precisely the day somebody asked for and
     * the bank could not pay for, which is the warning the floor wanted.
     *
     * <p>Requested days are covered FIRST by the allocation. Marking a specific
     * day is a choice the chronological rule cannot express: the person knows
     * which day should be bought back.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_outcome", length = 20)
    private AbsenceOutcome requestedOutcome;

    /**
     * The single work log written across the shift when this absence became ND.
     *
     * <p>Held so the reverse is exact: when the bank shrinks and the day stops
     * being a neradni dan, there is no guessing which row to take back out.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nd_work_log_id")
    private WorkLog ndWorkLog;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
