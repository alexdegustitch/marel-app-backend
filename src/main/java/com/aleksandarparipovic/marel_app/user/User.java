package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.role.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;


@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    // Nullable: Google-provisioned accounts have no local password.
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    // DB-generated (GENERATED ALWAYS AS first_name || ' ' || last_name) — read-only.
    // @Generated makes Hibernate re-read the value after an insert or update;
    // without it a freshly persisted User has fullName == null for the rest of the
    // transaction, which silently leaked a null name into notification payloads.
    @org.hibernate.annotations.Generated(event = {
            org.hibernate.generator.EventType.INSERT,
            org.hibernate.generator.EventType.UPDATE
    })
    @Column(name = "full_name", insertable = false, updatable = false)
    private String fullName;

    /**
     * An optional name to be shown by, apart from the legal first/last name.
     *
     * <p>NULL means "use the real name", which is the case for nearly every
     * account. It is a presentation preference and NEVER reaches a payroll
     * document: those carry the employee record's legal name, which is a
     * different record precisely so that this one can be informal.
     */
    @Column(name = "display_name", length = 150)
    private String displayName;

    @Column(name = "mobile_phone")
    private String mobilePhone;

    @Column(name = "email_address", nullable = false, unique = true)
    private String emailAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

    /**
     * The worker this account belongs to, when it belongs to one.
     *
     * <p>NULL for administration, payroll and the developer account — those are
     * not workers, and NULL is their permanent answer rather than a gap. Set by
     * an administrator; never inferred from a matching name or e-mail address,
     * because neither is unique and a wrong guess here hands somebody another
     * person's payslip.
     *
     * <p>The database holds one account per worker (uq_users_employee_id), so
     * "whose payslip is this" always has exactly one answer.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    /**
     * Authoritative workflow state. Persisted as a string (never an ordinal), in
     * line with the project's other enums (see {@code ProductionOrderStatus}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private UserAccountStatus accountStatus;

    /**
     * DERIVED from {@link #accountStatus} by trg_00_users_account_status_sync.
     * Kept because existing specifications, DTOs and the frontend read it. Never
     * set it as the way to express a workflow decision — set accountStatus.
     */
    @Column(name = "is_active")
    private Boolean active;

    /** First time the account reached ACTIVE. Set by the database, never cleared. */
    @Column(name = "activated_at", insertable = false)
    private OffsetDateTime activatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
