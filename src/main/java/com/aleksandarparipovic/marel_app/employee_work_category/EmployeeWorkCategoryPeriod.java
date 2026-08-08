package com.aleksandarparipovic.marel_app.employee_work_category;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One spell of an employee having a given default work category.
 *
 * <p>The authority for what somebody normally works in on a date; see
 * {@code 2026-09-22-01}. {@code employees.default_work_category_id} mirrors the
 * period in force and is maintained by trigger — never written from here.
 *
 * <p>Affects NO calculation. The work log carries its own category; this one
 * only pre-fills what a supervisor is offered.
 */
@Entity
@Table(name = "employee_work_category_periods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeWorkCategoryPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_code_category_id", nullable = false)
    private WorkCodeCategory workCodeCategory;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive, like every other period here. Null = still in force. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /** Maintained by trg_03_ewcp_updated_at, never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
