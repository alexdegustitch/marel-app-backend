package com.aleksandarparipovic.marel_app.bonus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BonusCategoryAmountPeriodRepository extends JpaRepository<BonusCategoryAmountPeriod, Long> {

    /** The spell covering {@code date} — what the payroll month pays. */
    @Query("""
            SELECT p FROM BonusCategoryAmountPeriod p
            WHERE p.bonusCategory.id = :categoryId
              AND p.archivedAt IS NULL
              AND p.validFrom <= :date
              AND (p.validTo IS NULL OR p.validTo >= :date)
            """)
    Optional<BonusCategoryAmountPeriod> findInForce(
            @Param("categoryId") Long categoryId,
            @Param("date") LocalDate date);

    /** The still-open spell — the amount in force today, the one an edit versions. */
    @Query("""
            SELECT p FROM BonusCategoryAmountPeriod p
            WHERE p.bonusCategory.id = :categoryId
              AND p.archivedAt IS NULL
              AND p.validTo IS NULL
            """)
    Optional<BonusCategoryAmountPeriod> findOpenFor(@Param("categoryId") Long categoryId);

    /** The category's full amount history, newest first. */
    @Query("""
            SELECT p FROM BonusCategoryAmountPeriod p
            WHERE p.bonusCategory.id = :categoryId
              AND p.archivedAt IS NULL
            ORDER BY p.validFrom DESC
            """)
    List<BonusCategoryAmountPeriod> findHistoryFor(@Param("categoryId") Long categoryId);
}
