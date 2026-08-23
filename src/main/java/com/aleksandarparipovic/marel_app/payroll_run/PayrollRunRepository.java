package com.aleksandarparipovic.marel_app.payroll_run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long>, JpaSpecificationExecutor<PayrollRun> {

    Optional<PayrollRun> findFirstByReportYearAndReportMonth(int year, int month);

    /**
     * Years that actually hold obračuni.
     *
     * <p>The Obračuni year view offered a fixed list starting at 2020, so years
     * without a single run were still shown as empty sections. Archived runs do
     * not count as a reason to keep a year on the page.
     */
    @Query("""
        SELECT DISTINCT pr.reportYear
        FROM PayrollRun pr
        WHERE pr.archivedAt IS NULL
        ORDER BY pr.reportYear DESC
        """)
    List<Integer> findYearsWithRuns();
}
