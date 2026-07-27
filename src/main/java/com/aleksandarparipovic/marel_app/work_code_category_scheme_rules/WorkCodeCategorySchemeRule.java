package com.aleksandarparipovic.marel_app.work_code_category_scheme_rules;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One compensation scheme's rule for one source work-code category.
 *
 * <p><strong>Not a replacement for
 * {@link com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping}.</strong>
 * The two answer different questions and both run:
 *
 * <ul>
 *   <li>this table — for THIS EMPLOYEE's scheme: may the category be used, what
 *       category does the base calculation use, what coefficient applies;</li>
 *   <li>{@code work_code_category_mappings} — given the CONTEXT of the work
 *       (night shift, weekend, parallel machines), what derived category does the
 *       SOURCE category produce.</li>
 * </ul>
 *
 * A fixed coefficient changes what the base row is worth. It does not delete a
 * night mapping.
 */
@Entity
@Table(name = "work_code_category_scheme_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkCodeCategorySchemeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compensation_scheme_id", nullable = false)
    private CompensationScheme compensationScheme;

    /** The category the employee actually worked. Never replaced. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_category_id", nullable = false)
    private WorkCodeCategory sourceCategory;

    /** {@code null} = the effective category is the source category itself. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "effective_category_id")
    private WorkCodeCategory effectiveCategory;

    @Column(name = "is_allowed", nullable = false)
    @Builder.Default
    private Boolean isAllowed = true;

    /**
     * {@code null} = fall through to the normal coefficient logic
     * ({@code work_code_categories.norm_multiplier}). {@code BigDecimal}, never a
     * float: this value multiplies paid minutes.
     *
     * <p>Scale 2, matching {@code work_logs.norm_multiplier_snapshot} — the
     * column a resolved coefficient is recorded in. A wider scale here could
     * express a value the snapshot cannot store.
     */
    @Column(name = "coefficient_override", precision = 10, scale = 2)
    private BigDecimal coefficientOverride;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive last day; {@code null} = open-ended. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;

    public boolean isUsableAt(LocalDate date) {
        return Boolean.TRUE.equals(isActive)
                && archivedAt == null
                && !date.isBefore(validFrom)
                && (validUntil == null || !date.isAfter(validUntil));
    }
}
