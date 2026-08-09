package com.aleksandarparipovic.marel_app.payroll_field_access;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Whether one role may see, and change, one payroll line.
 *
 * <p>Admin and developer have no rows here and never will — they bypass this
 * table, which is what lets a MISSING row mean hidden. The database refuses a
 * row for them, and refuses edit without view.
 */
@Entity
@Table(name = "payroll_field_access")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollFieldAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A payroll_adjustment_categories.code, or an item-level figure. */
    @Column(name = "field_code", nullable = false)
    private String fieldCode;

    @Column(name = "role_name", nullable = false)
    private String roleName;

    @Column(name = "can_view", nullable = false)
    private boolean canView;

    @Column(name = "can_edit", nullable = false)
    private boolean canEdit;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
