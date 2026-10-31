package com.aleksandarparipovic.marel_app.admin_schedule;

import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * One day of one ADMINISTRATOR'S roster: which shift a supervisor-role USER
 * works on {@code workDate}. Deliberately not an employee record — work_shifts
 * and the V50/V67 period tables stay the factory floor's, and nothing here
 * reaches payroll.
 *
 * <p>More than one row per person per day is legal (shift I then shift II).
 * {@code startTime}/{@code endTime}, when set, narrow the row to a window
 * INSIDE the shift ("prva smena, ali samo prva 2h"); a NULL pair means the
 * whole shift, whose hours stay the šifarnik's business and are never copied
 * here. Removal archives, never deletes (V68).
 */
@Entity
@Table(name = "admin_schedule_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    /** NULL = the whole shift; set = this window inside it (pair with endTime). */
    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /** Maintained by trg_03_ase_updated_at, never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archived_by")
    private Long archivedBy;
}
