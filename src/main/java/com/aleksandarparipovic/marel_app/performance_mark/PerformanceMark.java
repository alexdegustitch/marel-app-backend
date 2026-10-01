package com.aleksandarparipovic.marel_app.performance_mark;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One dated VERSION of a performance mark's value: what the mark (ocena) does
 * to the hourly rate of whoever receives it, and for which dates that reading
 * holds. Editing a mark's value never rewrites a row a payroll already read —
 * the service closes the open period and opens a new one (V57's rule).
 *
 * <p>Nothing here assigns a mark to an employee; the monthly per-employee
 * assignment inside the payroll flow comes later and will reference this
 * šifarnik.
 */
@Entity
@Table(name = "performance_marks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerformanceMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The mark itself, e.g. 4.50 — the row's identity across versions. */
    @Column(name = "mark", nullable = false, precision = 5, scale = 2)
    private BigDecimal mark;

    /** The sign is part of the value: negative = deduction, positive = bonus. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_unit", nullable = false, length = 16)
    private PerformanceMarkAmountUnit amountUnit;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive, like every other period here. Null = still in force. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /** Maintained by trg_03_pm_updated_at, never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archived_by")
    private Long archivedBy;
}
