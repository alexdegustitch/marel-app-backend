package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "work_shifts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_work_shifts_employee_shift_work_date",
                columnNames = {"employee_id", "shift_id", "work_date"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Employee
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    // Employee record snapshot used for historical tracking and auditing.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_record_id")
    private EmployeeRecord employeeRecord;

    // Shift definition
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    // Supervisor
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_id")
    private User supervisor;

    // Work code category for this shift as entered (original; never overwritten by recalc).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_code_category_id")
    private WorkCodeCategory workCodeCategory;

    // Bonus-effective category set by recalc when a night/weekend remap applies.
    // NULL = no active remap (use workCodeCategory). Recomputed every recalc, so it
    // reverts automatically when the bonus condition no longer holds.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "effective_work_code_category_id")
    private WorkCodeCategory effectiveWorkCodeCategory;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    // ❗ trenutno NIJE generated u DB → običan column
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    // GENERATED → read-only
    @Column(name = "day_of_week", insertable = false, updatable = false)
    private Short dayOfWeek;

    @Column(name = "total_minutes", insertable = false, updatable = false)
    private Integer totalMinutes;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_activity_at", insertable = false)
    private OffsetDateTime lastActivityAt;

    @Column(name = "is_active")
    private Boolean isActive = true;

    // 🔥 optimistic locking
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}