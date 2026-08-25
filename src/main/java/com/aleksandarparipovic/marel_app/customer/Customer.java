package com.aleksandarparipovic.marel_app.customer;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Who the factory makes things for.
 *
 * <p>Only the name is required. A customer known by nothing but their name is a
 * customer, and the alternative — demanding a code or a tax id before anybody
 * can be recorded — is how people end up typing the customer into an order's
 * free-text name instead, which is the state this table replaces.
 *
 * <p><b>Deactivated, never deleted.</b> Orders reference the customer they were
 * made for, and that history has to survive somebody who stopped being a
 * customer years ago. The foreign keys carry no ON DELETE for the same reason:
 * the database refuses a delete rather than cutting the orders loose.
 *
 * <p>The three timestamps are the DATABASE's, not this class's — `archived_at`
 * and `updated_at` are written by triggers, so they are read here and never
 * inserted. Letting JPA write them would mean two authors for one value, and the
 * one that lost would be whichever ran second.
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optional in-house short code; unique case-insensitively among those that have one. */
    @Column(name = "code", length = 50)
    private String code;

    // DB: NOT NULL + check length(btrim(name)) > 0
    @Column(name = "name", nullable = false)
    private String name;

    /** Optional tax identification number; unique among those that have one. */
    @Column(name = "tax_id", length = 50)
    private String taxId;

    @Column(name = "website", length = 500)
    private String website;

    @Column(name = "email")
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;
}
