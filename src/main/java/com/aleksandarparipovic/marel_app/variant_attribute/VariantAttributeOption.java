package com.aleksandarparipovic.marel_app.variant_attribute;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One value a variant attribute can take: "Prohrom", "Al", "Cu", "Ms".
 *
 * <p>An archived option is not offered for NEW variants or bindings; the
 * existing ones that picked it stay valid — the šifarnik pattern used
 * everywhere else in this application.
 */
@Entity
@Table(name = "variant_attribute_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VariantAttributeOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_attribute_id", nullable = false)
    private VariantAttribute attribute;

    /** "Prohrom", "Al" — unique per attribute, case-insensitively, among live ones. */
    @Column(name = "value", nullable = false, length = 100)
    private String value;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void archive() {
        this.isActive = false;
        this.archivedAt = OffsetDateTime.now();
    }

    public void restore() {
        this.isActive = true;
        this.archivedAt = null;
    }
}
