package com.aleksandarparipovic.marel_app.payroll_adjustment;

import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(
    name = "payroll_adjustments",
    uniqueConstraints = @UniqueConstraint(columnNames = {"payroll_run_item_id", "payroll_adjustment_category_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_item_id", nullable = false)
    private PayrollRunItem payrollRunItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_adjustment_category_id", nullable = false)
    private PayrollAdjustmentCategory payrollAdjustmentCategory;

    /** System-calculated quantity (e.g. number of eligible shifts for meal allowance) */
    @Column(name = "system_quantity")
    private BigDecimal systemQuantity;

    /** Currently active quantity — equals system_quantity unless overridden */
    @Column(name = "quantity")
    private BigDecimal quantity;

    /** System-calculated unit amount (e.g. meal allowance rate per shift) */
    @Column(name = "system_unit_amount")
    private BigDecimal systemUnitAmount;

    /** Currently active unit amount — equals system_unit_amount unless overridden */
    @Column(name = "unit_amount")
    private BigDecimal unitAmount;

    /** System-calculated total amount */
    @Column(name = "system_amount")
    private BigDecimal systemAmount;

    /** Currently active/final amount — equals system_amount unless overridden */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /** True when user has manually overridden the system value */
    @Column(name = "is_overridden", nullable = false)
    private Boolean isOverridden = false;

    @Column(name = "note")
    private String note;

    @Column(name = "is_applied", nullable = false)
    private Boolean isApplied = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edited_by")
    private User editedBy;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
