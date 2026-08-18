package com.aleksandarparipovic.marel_app.operation_norm_version;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One norm an operation has had.
 *
 * <p>The operation's own columns carry the CURRENT norm and keep doing so —
 * payroll and the manufacturing-time report read them. This table is the history
 * behind that value: what it was, from which date, who entered it, and whether
 * anyone verified ("overio") it and when.
 *
 * <p>Verification is deliberately its own pair of fields rather than a flag:
 * "verified" without a person and a moment is not a fact anyone can audit.
 */
@Entity
@Table(name = "operation_norm_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationNormVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    @Column(name = "min_norm")
    private Integer minNorm;

    @Column(name = "max_norm")
    private Integer maxNorm;

    @Column(name = "units_per_product")
    private Integer unitsPerProduct;

    /** The date the norm applies from — the existing "datum norme". */
    @Column(name = "norm_date")
    private LocalDate normDate;

    @Column(name = "note")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /*
     * Database-defaulted. `@Generated` makes Hibernate SELECT it back after the
     * insert — without it the row we just wrote reads as having no timestamp for
     * the rest of the session, and the response to "add a norm" would carry a
     * null date straight to the screen.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean isVerified() {
        return verifiedAt != null;
    }
}
