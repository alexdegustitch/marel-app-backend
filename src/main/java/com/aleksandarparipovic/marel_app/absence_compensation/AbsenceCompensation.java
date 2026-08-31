package com.aleksandarparipovic.marel_app.absence_compensation;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecord;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecord;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One sentence: this many minutes of THAT overtime day paid for THIS absence.
 *
 * <p>Three hours missed on the 21st, two of them covered by the 12th and one by
 * the 10th, is three of these rows' worth of answer — and the reason the link is
 * to an overtime DAY rather than to a shift, which could only ever have said
 * "compensated somewhere".
 *
 * <p><b>Derived.</b> {@code AbsenceCompensationAllocator} rebuilds every row for
 * an employee-month whenever the bank or the absences move. Nothing here is
 * entered by a person, and nothing here is soft-deleted: see the note on
 * {@code archived_at} in V25.
 */
@Entity
@Table(
        name = "absence_compensations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_absence_comp_overtime",
                columnNames = {"absence_record_id", "overtime_record_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbsenceCompensation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "absence_record_id", nullable = false)
    private AbsenceRecord absenceRecord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "overtime_record_id", nullable = false)
    private OvertimeRecord overtimeRecord;

    /** Strictly positive: chk_comp_minutes_positive. */
    @Column(name = "compensated_minutes", nullable = false)
    private Integer compensatedMinutes;

    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
