package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "work_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Parent shift
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_shift_id", nullable = false)
    private WorkShift workShift;

    // Operation performed
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    // Production order
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_id")
    private ProductionOrder productionOrder;

    // ── The three category concepts. They must not be merged. ───────────────
    //
    // 1. THE SOURCE CATEGORY: what the employee actually worked, as entered by
    //    the user. Never overwritten by recalc, never replaced by either of the
    //    two below.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_code_category_id", nullable = false)
    private WorkCodeCategory workCode;

    // 2. THE DERIVED / CONTEXTUAL CATEGORY: the reversible night/weekend remap
    //    produced by work_code_category_mappings, recomputed on every recalc.
    //    Resolved from the SOURCE category and deliberately unaffected by the
    //    compensation scheme — a fixed coefficient must not delete a night
    //    mapping. NULL = no active remap.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "effective_work_code_category_id")
    private WorkCodeCategory effectiveWorkCode;

    // 3. THE SCHEME-EFFECTIVE CATEGORY: the category the employee-specific base
    //    calculation uses after applying the compensation scheme. NULL = the
    //    scheme did not remap anything, i.e. it equals the source category.
    //    Written once from the resolver and then left alone, because it is a
    //    historical snapshot: changing the employee's scheme later must not
    //    change what this work was worth.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scheme_effective_work_code_category_id")
    private WorkCodeCategory schemeEffectiveWorkCode;

    // Which scheme and which rule produced the result above, so an old
    // calculation can still be explained after the rule has been superseded.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compensation_scheme_id")
    private CompensationScheme compensationScheme;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_code_category_scheme_rule_id")
    private WorkCodeCategorySchemeRule workCodeCategorySchemeRule;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    // Generated column. @Generated makes Hibernate SELECT it back after the write:
    // without it a freshly saved log carries null here for the rest of the
    // session, and anything reading it in the same transaction — the analytics
    // fact sync does — writes that null on. Same fix as User.fullName.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "duration_min", insertable = false, updatable = false)
    private Integer durationMin;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "scrap")
    private Integer scrap;

    @Column(name = "note")
    private String note;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // Generated column — read back for the same reason as duration_min above.
    // This one also feeds the performance rate, so a null would measure as 100 %.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "hourly_output", insertable = false, updatable = false)
    private BigDecimal hourlyOutput;

    /**
     * The resolved coefficient in force when this work was recorded: the scheme
     * rule's override when one applied, otherwise the source category's own
     * {@code norm_multiplier}.
     *
     * <p>Authoritative for every later calculation of this log. Reading the
     * category's current multiplier instead would silently re-price historical
     * work whenever an administrator edits a category.
     */
    @Column(name = "norm_multiplier_snapshot", precision = 38, scale = 2)
    private BigDecimal normMultiplierSnapshot;

    @Column(name = "performance_rate", precision = 38, scale = 2)
    private BigDecimal performanceRate;

    @Column(name = "approved_performance_rate", precision = 38, scale = 2)
    private BigDecimal approvedPerformanceRate;

    @Column(name = "paid_minutes", precision = 38, scale = 2)
    private BigDecimal paidMinutes;

    // DB-managed timestamps
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}

