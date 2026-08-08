package com.aleksandarparipovic.marel_app.work_code_category_mappings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A kind of contextual remap, and whether it applies while an employee is on
 * probation.
 *
 * <p>The registry exists for two reasons. It carries
 * {@link #appliesDuringProbation}, which is a property of the remap KIND rather
 * than of any one source→target row — "no weekend bonus on probation", not
 * "J→JB does not fire". And it gives {@code mapping_type} a foreign key, which
 * it never had: {@code DailyRecalcService}'s switch ends in
 * {@code default -> ignore}, so before this a typo produced a mapping row that
 * looked configured and did nothing.
 */
@Entity
@Table(name = "work_code_category_mapping_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkCodeCategoryMappingType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /**
     * FALSE withholds this remap for an employee inside their probation period.
     *
     * <p>Defaults TRUE so a type added later cannot silently withhold a bonus
     * nobody meant to withhold.
     */
    @Column(name = "applies_during_probation", nullable = false)
    @Builder.Default
    private Boolean appliesDuringProbation = true;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
