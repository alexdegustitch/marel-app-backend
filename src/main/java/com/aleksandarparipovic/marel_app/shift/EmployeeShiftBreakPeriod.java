package com.aleksandarparipovic.marel_app.shift;

import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * One spell of WHEN the pauza falls inside a shift — for one employee, or by
 * default. The exact sibling of {@link EmployeeShiftTimePeriod}: {@code
 * employee} set → that person's own break window; NULL → the shift's default,
 * versioned.
 *
 * <p>Affects only what the break WRITER draws next (see {@code
 * BreakLogWriter}); it never rewrites a karton row already written. Unlike
 * shift times there is no fallback beyond the default row — a shift without a
 * configured break has none.
 */
@Entity
@Table(name = "employee_shift_break_periods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeShiftBreakPeriod {

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

    /** May be before startTime — shift III's break crosses midnight. */
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

    /** Maintained by trg_03_esbp_updated_at, never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archived_by")
    private Long archivedBy;
}
