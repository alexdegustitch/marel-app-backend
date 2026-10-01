package com.aleksandarparipovic.marel_app.performance_mark;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface PerformanceMarkRepository extends JpaRepository<PerformanceMark, Long> {

    /** The admin listing: every version, archived included so it can be restored. */
    List<PerformanceMark> findAllByOrderByMarkAscValidFromDesc();

    /**
     * The versions in force on one date — the payroll screen's picker, asked
     * with the first day of the payroll month. One row per mark by
     * ex_pm_no_overlap, so there is nothing further to dedupe.
     */
    @Query("""
            SELECT p FROM PerformanceMark p
            WHERE p.archivedAt IS NULL
              AND p.validFrom <= :date
              AND (p.validTo IS NULL OR p.validTo >= :date)
            ORDER BY p.mark ASC
            """)
    List<PerformanceMark> findValidOn(@Param("date") LocalDate date);

    /**
     * Live rows of the SAME mark whose period would overlap [validFrom, validTo],
     * excluding the row being edited. Mirrors ex_pm_no_overlap so the refusal can
     * speak Serbian instead of surfacing a constraint violation.
     *
     * <p>No nullable parameters — Postgres cannot type a null date in
     * ":p IS NULL OR …" — so the service passes sentinels: LocalDate.MAX for an
     * open-ended validTo and -1 for "exclude nothing".
     */
    @Query("""
            SELECT p FROM PerformanceMark p
            WHERE p.archivedAt IS NULL
              AND p.mark = :mark
              AND p.id <> :excludeId
              AND p.validFrom <= :validTo
              AND (p.validTo IS NULL OR p.validTo >= :validFrom)
            """)
    List<PerformanceMark> findOverlapping(@Param("mark") BigDecimal mark,
                                          @Param("validFrom") LocalDate validFrom,
                                          @Param("validTo") LocalDate validTo,
                                          @Param("excludeId") Long excludeId);
}
