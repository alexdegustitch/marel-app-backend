package com.aleksandarparipovic.marel_app.employee_payroll_value;

import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * What one employee's value was, over one period.
 *
 * <p><b>Never updated in place.</b> A change closes the open period and opens a
 * new one, in a single transaction — see
 * {@link EmployeePayrollValueService#changeValue}. That is what makes
 * recalculating an old month reproduce the number that was actually paid.
 *
 * <p>{@link #validUntil} is the <b>inclusive</b> last day, matching
 * {@code employee_compensation_scheme_history}. {@code app_settings} uses the
 * opposite convention on purpose: it is a continuous type. See D2a in the
 * migration plan.
 */
@Entity
@Table(name = "employee_payroll_value_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeePayrollValueHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "value_definition_id", nullable = false)
    private EmployeePayrollValueDefinition definition;

    /**
     * Mirrors {@link #definition}'s own {@code value_type}.
     *
     * <p>A plain column rather than a derived getter because the database binds the
     * two with a COMPOSITE foreign key on {@code (value_definition_id, value_type)}.
     * That is what makes "the populated value column matches the declared type" a
     * guarantee instead of a convention — there is no way to write a numeric value
     * against a TEXT definition, and no trigger is needed to keep it true.
     */
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    @Column(name = "numeric_value", precision = 38, scale = 6)
    private BigDecimal numericValue;

    @Column(name = "boolean_value")
    private Boolean booleanValue;

    @Column(name = "text_value", columnDefinition = "TEXT")
    private String textValue;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** INCLUSIVE last day the value applies; {@code null} means open-ended. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /** True when this period covers {@code date}, both bounds inclusive. */
    public boolean coversInclusive(LocalDate date) {
        return archivedAt == null
                && !validFrom.isAfter(date)
                && (validUntil == null || !validUntil.isBefore(date));
    }
}
