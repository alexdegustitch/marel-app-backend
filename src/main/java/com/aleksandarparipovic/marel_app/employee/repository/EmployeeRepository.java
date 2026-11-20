package com.aleksandarparipovic.marel_app.employee.repository;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository
        extends JpaRepository<Employee, Long>,
        JpaSpecificationExecutor<Employee>,
        EmployeeRepositoryCustom {

    boolean existsByEmployeeNo(String employeeNo);

    List<Employee> findAllByActiveTrueAndArchivedAtIsNull();

    Page<EmployeeWithBonusView> findAllProjectedBy(
            Specification<Employee> spec,
            Pageable pageable
    );
    @Query("""
    select
      e.id as employeeId,
      e.employeeNo as employeeNo,
      e.firstName as firstName,
      e.lastName as lastName,
      e.fullName as fullName,
      d.name as departmentName,
      d.id as departmentId,
      e.employmentStartDate as employmentStartDate,
      e.probationEndDate as probationEndDate,
      e.notes as notes,
      e.transportAllowanceRsd as transportAllowanceRsd,
      e.transportAllowanceMode as transportAllowanceMode,
      bc.categoryNo as categoryNo,
      bc.id as categoryId,
      bc.categoryName as categoryName,
      bc.bonusAmount as bonusAmount,
      eb.startDate as bonusStart,
      cs.code as schemeCode,
      cs.name as schemeName,
      cs.allowsPerformanceBonus as allowsPerformanceBonus,
      e.mobilePhone as mobilePhone,
      e.email as email,
      e.hourlyRate as hourlyRate,
      wcc.id as defaultWorkCategoryId,
      wcc.categoryName as defaultWorkCategoryName,
      e.preferredLocale as preferredLocale
    
    from Employee e
    join e.department d
    
    left join EmployeeBonus eb
      on eb.employee = e and eb.endDate is null
    
    left join eb.bonusCategory bc
      on bc.archivedAt is null
     and current_date between bc.validFrom and coalesce(bc.validUntil, '9999-12-31')
    
    left join e.compensationSchemePeriods csp
      on csp.validUntil is null and csp.archivedAt is null
    left join csp.compensationScheme cs

    left join e.defaultWorkCategory wcc
    
    where e.archivedAt is null
    order by e.id asc
    """)
    Page<EmployeeWithBonusView> findEmployeesWithCurrentBonus(Pageable pageable);

    /**
     * One employee, by id, for the three methods that RETURN what they just wrote.
     *
     * <p><b>No {@code archivedAt is null} here, unlike the listing above, and that
     * is the point.</b> {@code trg_02_employees_archived_at} stamps
     * {@code archived_at} the moment {@code is_active} goes false, so with that
     * filter the deactivating PATCH saved correctly and then answered 404 through
     * this very query — the change was made and the caller was told it was not.
     *
     * <p>Nothing is widened by dropping it: every caller is a write that has
     * already happened, addressing an employee by an id it was just given. Hiding
     * an archived employee is a LISTING rule, and the listing keeps it.
     */
    @Query("""
    select
      e.id as employeeId,
      e.employeeNo as employeeNo,
      e.firstName as firstName,
      e.lastName as lastName,
      e.fullName as fullName,
      d.name as departmentName,
      d.id as departmentId,
      e.employmentStartDate as employmentStartDate,
      e.probationEndDate as probationEndDate,
      e.notes as notes,
      e.transportAllowanceRsd as transportAllowanceRsd,
      e.transportAllowanceMode as transportAllowanceMode,
      bc.categoryNo as categoryNo,
      bc.id as categoryId,
      bc.categoryName as categoryName,
      bc.bonusAmount as bonusAmount,
      eb.startDate as bonusStart,
      cs.code as schemeCode,
      cs.name as schemeName,
      cs.allowsPerformanceBonus as allowsPerformanceBonus,
      e.mobilePhone as mobilePhone,
      e.email as email,
      e.hourlyRate as hourlyRate,
      wcc.id as defaultWorkCategoryId,
      wcc.categoryName as defaultWorkCategoryName,
      e.preferredLocale as preferredLocale
    from Employee e
    join e.department d
    left join EmployeeBonus eb
      on eb.employee = e and eb.endDate is null
    left join eb.bonusCategory bc
      on bc.archivedAt is null
     and current_date between bc.validFrom and coalesce(bc.validUntil, '9999-12-31')
    left join e.compensationSchemePeriods csp
      on csp.validUntil is null and csp.archivedAt is null
    left join csp.compensationScheme cs

    left join e.defaultWorkCategory wcc
    where e.id = :id
    """)
    Optional<EmployeeWithBonusView> findEmployeeWithBonusById(@Param("id") Long id);

    @Query("SELECT e FROM Employee e WHERE e.active = true AND e.archivedAt IS NULL AND e.employmentEndDate IS NOT NULL AND e.employmentEndDate <= :today")
    List<Employee> findActiveEmployeesWithExpiredEndDate(@Param("today") LocalDate today);

    @Query("SELECT e FROM Employee e LEFT JOIN FETCH e.department LEFT JOIN FETCH e.employeeBonuses b LEFT JOIN FETCH b.bonusCategory LEFT JOIN FETCH e.defaultWorkCategory WHERE e.id = :id")
    Optional<Employee> findByIdWithDetails(@Param("id") Long id);

    /**
     * Employees whose full name or number contains {@code q}, for the global
     * command-palette search. Case-insensitive, live employees only, exact
     * number matches first; the caller caps the page.
     */
    @Query("""
        select e.id as id,
               e.fullName as fullName,
               e.employeeNo as employeeNo,
               d.name as departmentName
        from Employee e
        join e.department d
        where e.archivedAt is null
          and (lower(e.fullName)   like lower(concat('%', :q, '%'))
            or lower(e.employeeNo) like lower(concat('%', :q, '%'))
            or lower(coalesce(e.mobilePhone, '')) like lower(concat('%', :q, '%'))
            or lower(coalesce(e.email, ''))       like lower(concat('%', :q, '%')))
        order by case when lower(e.employeeNo) = lower(:q) then 0 else 1 end,
                 e.fullName asc, e.id asc
        """)
    List<com.aleksandarparipovic.marel_app.search.dto.EmployeeSearchRow> searchTop(
            @Param("q") String q, Pageable pageable);

    /**
     * Diacritic-folding, any-token match for Sparky's fuzzy resolver. Both sides
     * are folded — the columns with Postgres {@code translate(lower(...))} (core
     * function, no extension), the query in Java — so a name typed without
     * diacritics still resolves. A row matches when its folded name or number
     * contains ANY whitespace token of {@code folded}; the caller ranks and caps.
     * Live employees only; capped at 25.
     */
    @Query(value = """
            SELECT e.id          AS id,
                   e.full_name   AS fullName,
                   e.employee_no AS employeeNo,
                   d.name        AS departmentName
            FROM employees e
            JOIN departments d ON d.id = e.department_id
            WHERE e.archived_at IS NULL
              AND EXISTS (
                  SELECT 1
                  FROM regexp_split_to_table(trim(:folded), '\\s+') AS tok
                  WHERE tok <> ''
                    AND (translate(lower(e.full_name), 'čćđšžČĆĐŠŽ', 'ccdszccdsz') LIKE '%' || tok || '%'
                      OR translate(lower(coalesce(e.employee_no, '')), 'čćđšžČĆĐŠŽ', 'ccdszccdsz') LIKE '%' || tok || '%')
              )
            ORDER BY e.full_name ASC, e.id ASC
            LIMIT 25
            """, nativeQuery = true)
    List<com.aleksandarparipovic.marel_app.search.dto.EmployeeSearchRow> searchFolded(
            @Param("folded") String folded);

    /**
     * Workers employed during the month — the whole month or part of it — who
     * have no obračun for it. Sending a month with such a worker is refused:
     * complete-looking payroll with somebody missing is exactly what the
     * director cannot notice.
     *
     * <p>"Employed during the month" is read from the employment-period history
     * where it exists, and from the employee's own dates otherwise — a worker
     * who left mid-month counts even though is_active has since gone false, and
     * one hired for NEXT month does not count yet. Archived workers never count.
     *
     * <p>An obračun created and archived does not satisfy the check, matching
     * every list that ignores archived items.
     */
    @Query(value = """
        SELECT e.full_name
        FROM employees e
        WHERE e.archived_at IS NULL
          AND (
              EXISTS (
                  SELECT 1 FROM employee_employment_periods p
                  WHERE p.employee_id = e.id
                    AND p.started_on <= :monthEnd
                    AND (p.ended_on IS NULL OR p.ended_on >= :monthStart)
              )
              OR (
                  e.is_active = TRUE
                  AND e.employment_start_date <= :monthEnd
                  AND (e.employment_end_date IS NULL OR e.employment_end_date >= :monthStart)
              )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM payroll_run_items pri
              JOIN payroll_runs pr ON pr.id = pri.payroll_run_id
              WHERE pri.employee_id = e.id
                AND pr.report_year  = :year
                AND pr.report_month = :month
                AND pri.archived_at IS NULL
          )
        ORDER BY e.full_name
        """, nativeQuery = true)
    List<String> findEmployedWithoutPayrollForMonth(@Param("year") int year,
                                                    @Param("month") int month,
                                                    @Param("monthStart") LocalDate monthStart,
                                                    @Param("monthEnd") LocalDate monthEnd);

}
