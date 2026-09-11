package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * One spell of WHEN a shift runs — for one employee, or by default.
 *
 * <p>{@code employee} set → that person's own hours for the shift (the worker
 * who standardly works shift I from 08 to 16 instead of 06 to 14).
 * {@code employee} NULL → the shift's DEFAULT hours, versioned, so "what should
 * somebody have worked on that date" stays answerable after the default moves;
 * {@code shifts.start_time/end_time} keep only the CURRENT value, as the mirror
 * every picker reads.
 *
 * <p>Affects only what SEEDS a {@code work_shifts} row (and what the boundary
 * recalculation shrinks back to). It never rewrites a row already written —
 * those are the material truth payroll reads. See {@link ShiftTimeResolver}.
 */
@Entity
@Table(name = "employee_shift_time_periods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeShiftTimePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = the default row for the shift; set = this employee's override. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** May be before startTime — an overnight shift, exactly as in shifts. */
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive, like every other period here. Null = still in force. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /** Maintained by trg_03_estp_updated_at, never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archived_by")
    private Long archivedBy;
}
