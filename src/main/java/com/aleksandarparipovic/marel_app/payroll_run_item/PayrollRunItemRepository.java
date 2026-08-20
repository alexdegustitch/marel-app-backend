package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunInfoDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemActivityDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollRunItemRepository extends JpaRepository<PayrollRunItem, Long>, JpaSpecificationExecutor<PayrollRunItem> {

    /** Fetches the item with its linked MonthlyReport in a single query — needed for version comparison. */
    @Query("SELECT pri FROM PayrollRunItem pri LEFT JOIN FETCH pri.monthlyReport WHERE pri.id = :id")
    Optional<PayrollRunItem> findByIdWithMonthlyReport(@Param("id") Long id);

    List<PayrollRunItem> findByPayrollRun_IdAndEmployee_Id(Long payrollRunId, Long employeeId);

    List<PayrollRunItem> findByPayrollRun_Id(Long payrollRunId);

    Optional<PayrollRunItem> findByMonthlyReport_Id(Long monthlyReportId);

    /** Used to fetch previous month's netPayableAmount for the same employee. */
    @Query("SELECT pri FROM PayrollRunItem pri WHERE pri.employee.id = :employeeId AND pri.period = :period ORDER BY pri.id DESC")
    List<PayrollRunItem> findByEmployee_IdAndPeriod(@Param("employeeId") Long employeeId, @Param("period") LocalDate period);

    /** Used to propagate changes to the next month's item. */
    @Query("SELECT pri FROM PayrollRunItem pri WHERE pri.employee.id = :employeeId AND pri.period = :period AND pri.status != 'LOCKED' ORDER BY pri.id DESC")
    List<PayrollRunItem> findUnlockedByEmployee_IdAndPeriod(@Param("employeeId") Long employeeId, @Param("period") LocalDate period);

    @Query("SELECT pri FROM PayrollRunItem pri LEFT JOIN FETCH pri.monthlyReport WHERE pri.employee.id = :employeeId ORDER BY pri.period DESC")
    List<PayrollRunItem> findRecentByEmployeeId(@Param("employeeId") Long employeeId, Pageable pageable);

    /** Returns summary (id, employeeId, employeeName, month, year) for all items in a given year. */
    @Query(value = """
        SELECT
            pri.id            AS id,
            e.id              AS employeeId,
            e.full_name       AS employeeName,
            pr.report_month   AS month,
            pr.report_year    AS year
        FROM payroll_run_items pri
        JOIN employees e  ON e.id  = pri.employee_id
        JOIN payroll_runs pr ON pr.id = pri.payroll_run_id
        WHERE pr.report_year = :year
          AND pri.archived_at IS NULL
        ORDER BY pr.report_month DESC, e.full_name ASC
        """, nativeQuery = true)
    List<PayrollRunSummaryDto> findSummariesByYear(@Param("year") int year);

    /** Returns last-activity summaries for the current user from employee_payroll_run_item_updates. */
    @Query(value = """
        SELECT
            pri.id            AS id,
            e.id              AS employeeId,
            e.full_name       AS employeeName,
            pr.report_month   AS month,
            pr.report_year    AS year
        FROM employee_payroll_run_item_updates epriu
        JOIN payroll_run_items pri ON pri.id = epriu.payroll_run_item_id
        JOIN employees e           ON e.id   = pri.employee_id
        JOIN payroll_runs pr       ON pr.id  = pri.payroll_run_id
        WHERE epriu.user_id = :userId
          AND pr.report_year  = :year
          AND pr.report_month = :month
          AND pri.archived_at IS NULL
        ORDER BY epriu.last_activity_at DESC
        LIMIT 3
        """, nativeQuery = true)
    List<PayrollRunSummaryDto> findLastActivityByUserAndMonth(@Param("userId") Long userId,
                                                              @Param("year") int year,
                                                              @Param("month") int month);

    /** Paged query for payroll run items by year + month with optional global search and status filter. */
    @Query(value = """
        SELECT
            pri.id              AS id,
            e.id                AS employeeId,
            e.full_name         AS employeeName,
            e.employee_no       AS employeeNo,
            d.name              AS employeeDepartment,
            pri.status          AS status,
            pri.total_net_earnings  AS totalNetEarnings,
            pri.net_payable_amount  AS netPayableAmount,
            pri.monthly_report_id   AS monthlyReportId,
            MAX(epriu.last_activity_at) AS updatedAt
        FROM payroll_run_items pri
        JOIN employees e    ON e.id  = pri.employee_id
        JOIN departments d  ON d.id  = e.department_id
        JOIN payroll_runs pr ON pr.id = pri.payroll_run_id
        LEFT JOIN employee_payroll_run_item_updates epriu ON epriu.payroll_run_item_id = pri.id
        WHERE pr.report_year  = :year
          AND pr.report_month = :month
          AND pri.archived_at IS NULL
          AND (:search IS NULL OR e.full_name ILIKE '%' || :search || '%' OR e.employee_no ILIKE '%' || :search || '%')
          AND (:status IS NULL OR pri.status = :status)
        GROUP BY pri.id, e.id, e.full_name, e.employee_no, d.name,
                 pri.status, pri.total_net_earnings, pri.net_payable_amount, pri.monthly_report_id
        """,
        countQuery = """
        SELECT COUNT(pri.id)
        FROM payroll_run_items pri
        JOIN employees e    ON e.id  = pri.employee_id
        JOIN payroll_runs pr ON pr.id = pri.payroll_run_id
        WHERE pr.report_year  = :year
          AND pr.report_month = :month
          AND pri.archived_at IS NULL
          AND (:search IS NULL OR e.full_name ILIKE '%' || :search || '%' OR e.employee_no ILIKE '%' || :search || '%')
          AND (:status IS NULL OR pri.status = :status)
        """,
        nativeQuery = true)
    Page<PayrollRunInfoDto> findPagedByYearAndMonth(@Param("year") int year,
                                                    @Param("month") int month,
                                                    @Param("search") String search,
                                                    @Param("status") String status,
                                                    Pageable pageable);

    /** Returns last 3 payroll run items touched by the current user in the given month. */
    @Query(value = """
        SELECT
            pri.monthly_report_id   AS monthlyReportId,
            e.id                    AS employeeId,
            e.full_name             AS employeeName,
            pr.report_month         AS month,
            pr.report_year          AS year,
            epriu.last_activity_at  AS updateTime
        FROM employee_payroll_run_item_updates epriu
        JOIN payroll_run_items pri ON pri.id  = epriu.payroll_run_item_id
        JOIN employees e           ON e.id    = pri.employee_id
        JOIN payroll_runs pr       ON pr.id   = pri.payroll_run_id
        WHERE epriu.user_id    = :userId
          AND pr.report_year   = :year
          AND pr.report_month  = :month
          AND pri.archived_at IS NULL
        ORDER BY epriu.last_activity_at DESC
        LIMIT 3
        """, nativeQuery = true)
    List<PayrollRunItemActivityDto> findItemLastActivityByUserAndMonth(@Param("userId") Long userId,
                                                                       @Param("year") int year,
                                                                       @Param("month") int month);

    /**
     * Flags a whole month for recalculation — what a change to the month's rules asks for.
     *
     * <p>LOCKED items are excluded, as they are in the per-employee variant: a locked figure
     * is one somebody signed off, and marking it stale would either quietly rewrite an
     * approved number or leave a flag nothing will ever clear.
     */
    @Modifying
    @Query("""
        UPDATE PayrollRunItem pri
        SET pri.needsRecalculation = true
        WHERE pri.payrollRun.id IN (
            SELECT pr.id FROM PayrollRun pr
            WHERE pr.reportYear = :year AND pr.reportMonth = :month
        )
        AND pri.archivedAt IS NULL
        AND pri.status <> 'LOCKED'
        """)
    int markNeedsRecalculationByYearAndMonth(@Param("year") int year, @Param("month") int month);

    /**
     * Whether a month is closed — any locked item in it.
     *
     * <p>Asked before anything rewrites what that month is calculated FROM. A locked item is a
     * figure somebody signed off; changing the rule under it would leave the signature
     * attached to arithmetic nobody approved.
     */
    @Query("""
        SELECT count(pri) FROM PayrollRunItem pri
        WHERE pri.payrollRun.id IN (
            SELECT pr.id FROM PayrollRun pr
            WHERE pr.reportYear = :year AND pr.reportMonth = :month
        )
        AND pri.archivedAt IS NULL
        AND pri.status = 'LOCKED'
        """)
    long countLockedForMonth(@Param("year") int year, @Param("month") int month);

    @Modifying
    @Query("""
        UPDATE PayrollRunItem pri
        SET pri.needsRecalculation = true
        WHERE pri.employee.id = :employeeId
          AND pri.status != 'LOCKED'
          AND pri.archivedAt IS NULL
        """)
    int markNeedsRecalculationByEmployeeId(@Param("employeeId") Long employeeId);

    /**
     * One employee, one month.
     *
     * <p>The precise version of the two above, for a dated change that names the
     * months it touched. Marking every month of an employee would reprice ones
     * the change could not have reached.
     */
    @Modifying
    @Query("""
        UPDATE PayrollRunItem pri
        SET pri.needsRecalculation = true
        WHERE pri.employee.id = :employeeId
          AND YEAR(pri.period) = :year
          AND MONTH(pri.period) = :month
          AND pri.status != 'LOCKED'
          AND pri.archivedAt IS NULL
        """)
    int markNeedsRecalculationByEmployeeAndMonth(@Param("employeeId") Long employeeId,
                                                @Param("year") int year,
                                                @Param("month") int month);

    /**
     * Whether this employee's month is closed.
     *
     * <p>Asked before anything that would change what the month is built from.
     * A locked payroll is a record of what was paid and is never recalculated,
     * so the change is refused rather than accepted into a month that cannot
     * follow it.
     */
    @Query("""
        SELECT count(pri) FROM PayrollRunItem pri
        WHERE pri.employee.id = :employeeId
          AND YEAR(pri.period) = :year
          AND MONTH(pri.period) = :month
          AND pri.status = 'LOCKED'
          AND pri.archivedAt IS NULL
        """)
    long countLockedForEmployeeAndMonth(@Param("employeeId") Long employeeId,
                                        @Param("year") int year,
                                        @Param("month") int month);

    /**
     * @deprecated Retroactive repricing — do not use. Writing a rate onto items
     * this way overwrites months the rate was never in force for, which is the
     * defect employee_payroll_value_history exists to close. Record the rate with
     * {@code EmployeePayrollValueService.setValue} and call
     * {@link #markNeedsRecalculationByEmployeeId} instead: each item then
     * re-resolves the rate for ITS OWN month. Kept only so an existing caller
     * outside this repository is not silently removed.
     */
    @Deprecated
    @Modifying
    @Query("""
        UPDATE PayrollRunItem pri
        SET pri.hourlyRate = :newRate,
            pri.hourlyRateSystem = :newRate,
            pri.needsRecalculation = true
        WHERE pri.employee.id = :employeeId
          AND pri.status != 'LOCKED'
          AND pri.archivedAt IS NULL
          AND (pri.hourlyRateOverridden IS NULL OR pri.hourlyRateOverridden = false)
        """)
    int updateHourlyRateByEmployeeId(@Param("employeeId") Long employeeId,
                                     @Param("newRate") java.math.BigDecimal newRate);

    /**
     * Flag every item a maintenance sweep may recalculate.
     *
     * <p>LOCKED items are excluded, not skipped later: a locked item is an
     * immutable snapshot and getForPayrollAccess refuses to recompute it anyway.
     * Flagging one would leave needs_recalculation set forever on a row nothing
     * will ever clear.
     */
    @Modifying
    @Query("""
        UPDATE PayrollRunItem pri
        SET pri.needsRecalculation = true
        WHERE pri.archivedAt IS NULL
          AND pri.status <> 'LOCKED'
        """)
    int flagAllForRecalculation();

    @Query("""
        SELECT pri.id FROM PayrollRunItem pri
        WHERE pri.archivedAt IS NULL
          AND pri.status <> 'LOCKED'
        ORDER BY pri.id
        """)
    List<Long> findAllRecalculableIds();
}
