package com.aleksandarparipovic.marel_app.compensation_scheme;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A payroll calculation policy.
 *
 * <p>An employee is never attached to a scheme by a column on {@code employees}:
 * the link is a date-effective period in
 * {@link com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory},
 * so moving an employee to a different scheme cannot change what work already
 * done was worth.
 *
 * <p>A scheme is identified in code by {@link #code}, never by id. See
 * {@link CompensationSchemeCodes}.
 */
@Entity
@Table(name = "compensation_schemes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompensationScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /**
     * What happens to a source category that has no explicit
     * {@code work_code_category_scheme_rules} row for this scheme.
     *
     * <p>{@code true} — allowed, resolves to itself, normal coefficient logic.
     * This is what makes {@code STANDARD} behave exactly as the system did
     * before compensation schemes existed.
     *
     * <p>{@code false} — unavailable, and rejected if submitted directly.
     */
    @Column(name = "allow_unmapped_categories", nullable = false)
    private Boolean allowUnmappedCategories = true;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // DB-managed timestamps
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;

    public boolean isUsable() {
        return Boolean.TRUE.equals(isActive) && archivedAt == null;
    }
}
