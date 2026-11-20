package com.aleksandarparipovic.marel_app.bonus;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One spell of HOW MUCH a bonus category pays — the dated sibling of the V50/V67
 * shift periods. {@code validTo} is inclusive; NULL means still in force.
 *
 * <p>{@code MonthlyBonusCalculator} reads the spell covering the payroll month,
 * so editing an amount never changes what an older, unlocked month recomputes
 * to. {@code bonus_categories.bonus_amount} stays as the MIRROR of the open
 * spell: lists and pickers read it, no calculator does.
 */
@Entity
@Table(name = "bonus_category_amount_periods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BonusCategoryAmountPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bonus_category_id", nullable = false)
    private BonusCategory bonusCategory;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

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

    /** Maintained by trg_03_bcap_updated_at, never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archived_by")
    private Long archivedBy;
}
