package com.aleksandarparipovic.marel_app.work_code;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A translated display name for one {@link WorkCodeCategory} in one locale.
 *
 * <p>{@code work_code_categories.category_name} remains the default name and the
 * fallback: a missing row yields the Serbian name, never null. The category
 * <em>code</em> ({@code category_no}) is never translated — it is an identifier.
 *
 * <p>Only master/reference data gets a translation table. Transactional records
 * that reference a category resolve the name through the master row instead of
 * carrying a copy of it.
 */
@Entity
@Table(name = "work_code_category_translations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkCodeCategoryTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_code_category_id", nullable = false)
    private WorkCodeCategory workCodeCategory;

    @Column(name = "locale", nullable = false, length = 35)
    private String locale;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
