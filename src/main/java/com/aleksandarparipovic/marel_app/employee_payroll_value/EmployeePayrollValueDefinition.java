package com.aleksandarparipovic.marel_app.employee_payroll_value;

import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A kind of per-employee payroll value the system knows about.
 *
 * <p>A calculator may not invent a key. It asks for a registered
 * {@link #code}, so an unknown one fails at write time instead of resolving to
 * nothing and quietly paying zero.
 *
 * <p>Identified in code by {@link #code}, never by id. See
 * {@link EmployeePayrollValueCodes}.
 */
@Entity
@Table(name = "employee_payroll_value_definitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeePayrollValueDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** NUMERIC, BOOLEAN or TEXT. Bound to the history rows by a composite FK. */
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    /** RSD, PERCENT, COUNT... Display and sanity only; no arithmetic reads it. */
    @Column(name = "unit_code", length = 30)
    private String unitCode;

    /**
     * The payslip line this value belongs to, or {@code null} when the value is a
     * calculation input rather than a line — {@code HOURLY_RATE} prices work
     * categories and has no adjustment line of its own.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_adjustment_category_id")
    private PayrollAdjustmentCategory payrollAdjustmentCategory;

    /** Referenced by calculator code; must not be archived through the UI. */
    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private Boolean isSystem = true;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean isUsable() {
        return Boolean.TRUE.equals(isActive) && archivedAt == null;
    }
}
