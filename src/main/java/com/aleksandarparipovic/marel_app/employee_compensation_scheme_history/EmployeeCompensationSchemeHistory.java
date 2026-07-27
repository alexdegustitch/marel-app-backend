package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One period during which an employee was under one compensation scheme.
 *
 * <p>Date semantics: {@link #validFrom} inclusive, {@link #validUntil} inclusive,
 * {@code null} meaning open-ended. Work on the first day of a new period belongs
 * to the new scheme; work on the last day of the old one still belongs to the
 * old scheme.
 *
 * <p>Rows are never rewritten to change an employee's current scheme. A change
 * closes the open period ({@code validUntil = newFrom - 1}) and inserts a new
 * one, so the history stays a truthful record of what was applied when. Overlaps
 * are prevented by the {@code ex_ecsh_no_overlap} GiST exclusion constraint, not
 * by a check-then-insert that two concurrent transactions could both pass.
 */
@Entity
@Table(name = "employee_compensation_scheme_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeCompensationSchemeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compensation_scheme_id", nullable = false)
    private CompensationScheme compensationScheme;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive last day; {@code null} = open-ended. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean coversInclusive(LocalDate date) {
        return archivedAt == null
                && !date.isBefore(validFrom)
                && (validUntil == null || !date.isAfter(validUntil));
    }
}
