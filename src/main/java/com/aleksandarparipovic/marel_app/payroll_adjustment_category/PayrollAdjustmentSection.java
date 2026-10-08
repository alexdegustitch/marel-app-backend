package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A payslip section, carrying the one fact the money formula reads: its
 * {@link #direction}.
 *
 * <p>A CLOSED set, seeded by V63 and meant to stay that way: the formula in
 * {@code PayrollTotals} computes with directions, so a new row here adds
 * vocabulary, never arithmetic. What used to be two free-text columns per
 * category (section_code + impact_code) — which had already drifted, two
 * identical settlement lines carrying different impact codes — is now one FK
 * into six rows that cannot disagree with themselves.
 */
@Entity
@Table(name = "payroll_adjustment_sections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollAdjustmentSection {

    /** The line enters the earnings. */
    public static final String DIRECTION_PLUS = "PLUS";
    /** The line enters "previously paid". */
    public static final String DIRECTION_MINUS = "MINUS";
    /** The line is information and reaches no total. */
    public static final String DIRECTION_NONE = "NONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable system code, e.g. SETTLEMENTS. */
    @Column(name = "code", nullable = false, unique = true)
    private String code;

    /** Display name, e.g. Razduženja. */
    @Column(name = "name", nullable = false)
    private String name;

    /** PLUS, MINUS or NONE — what a line in this section does to the totals. */
    @Column(name = "direction", nullable = false)
    private String direction;

    /** Order of the section on the payslip. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** Whether people may add hand-made lines in this section. */
    @Column(name = "allows_manual_lines", nullable = false)
    private Boolean allowsManualLines = false;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
