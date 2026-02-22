package com.aleksandarparipovic.marel_app.bonus;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "bonus_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BonusCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_no", nullable = false)
    private String categoryNo;

    @Column(name = "category_name", nullable = false)
    private String categoryName;

    @Column(name = "bonus_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal bonusAmount;

    @Column(name = "min_hours", precision = 5, scale = 2)
    private BigDecimal minHours;

    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isCurrentlyValid() {
        LocalDate today = LocalDate.now();
        return !today.isBefore(validFrom)
                && (validUntil == null || !today.isAfter(validUntil));
    }

    public boolean isArchived() {
        return archivedAt != null;
    }
}
