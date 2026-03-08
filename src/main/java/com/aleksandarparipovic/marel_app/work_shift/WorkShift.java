package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "work_shifts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Employee who worked the shift
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    // Shift definition (morning / night)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    // Supervisor (user)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_id")
    private User supervisor;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    // Generated column
    @Column(name = "work_date", insertable = false, updatable = false)
    private LocalDate workDate;

    // Generated column
    @Column(name = "total_minutes", insertable = false, updatable = false)
    private Integer totalMinutes;

    @Column(name = "is_saturday", nullable = false)
    private Boolean isSaturday = false;

    // JSONB column
    @Column(name = "notes")
    private String notes;

    // Managed by DB
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    // Managed by trigger
    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    // Updated when shift or its work logs change
    @Column(name = "last_activity_at", nullable = false, insertable = false)
    private OffsetDateTime lastActivityAt;
}
