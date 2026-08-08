package com.aleksandarparipovic.marel_app.employee.view;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
public class EmployeeWithBonusView {

    private final Long employeeId;
    private final String employeeNo;
    private final String firstName;
    private final String lastName;
    /** Derived from the two parts by the database; never sent back on a write. */
    private final String fullName;
    private final String departmentName;
    private final Long departmentId;
    private final LocalDate employmentStartDate;
    private final LocalDate probationEndDate;
    private final String notes;
    private final BigDecimal transportAllowanceRsd;
    private final String transportAllowanceMode;
    private final String categoryNo;
    private final Long categoryId;
    private final String categoryName;
    private final BigDecimal bonusAmount;
    private final LocalDate bonusStart;
    /**
     * The compensation scheme in force today — code, display name, and whether it
     * earns a performance bonus. Null when the employee has no open period.
     *
     * <p>This is the single source for what kind of worker this is: since
     * {@code 2026-09-19-03} there are no is_foreigner / works_in_commercial
     * columns left to disagree with it.
     *
     * <p>The code travels to the client RAW, and no method here compares it to a
     * known value. Naming a scheme in Java is what
     * {@code docs/business-rules/compensation-schemes-and-category-localization.md}
     * §10 forbids and {@code NewCompensationSchemeIsDataOnlyIT} enforces — adding
     * a worker type must stay a data change. Which badge or colour a code earns
     * is a presentation decision and is made on the client.
     *
     * <p>{@code allowsPerformanceBonus} is likewise carried as DATA rather than
     * inferred: it is the column that already says whether a bonus category means
     * anything for this employee, so a scheme added tomorrow gets the right
     * behaviour without a line of code.
     */
    private final String schemeCode;
    private final String schemeName;
    private final Boolean allowsPerformanceBonus;
    private final String mobilePhone;
    private final String email;
    private final BigDecimal hourlyRate;
    private final Long defaultWorkCategoryId;
    private final String defaultWorkCategoryName;

    /** Document language for this employee's payslip. Never affects an amount. */
    private final String preferredLocale;

    /**
     * Whether a department-head period covers today.
     *
     * <p>NOT final and NOT part of the constructor, unlike every other field
     * here, because it cannot be selected with them. Hibernate 6 cannot render a
     * correlated subquery inside a constructor SELECT — three different shapes
     * (entity equality, correlate()+join, and a foreign-key comparison) all fail
     * with "Could not locate TableGroup" or "Already registered a copy". So the
     * flag is filled by DepartmentHeadEnricher once per PAGE, which is one extra
     * query rather than one per row.
     */
    @lombok.Setter
    private boolean departmentHead;

    /**
     * The hourly rate in force TODAY.
     *
     * <p>Filled by the enricher for the same reason as departmentHead, and read
     * from the same place payroll reads it: {@code employee_payroll_value_history}
     * resolved by date, falling back to {@code employees.hourly_rate}. That
     * column is NOT the authority — PayrollRunItemService.hourlyRateFor logs it
     * as "not period-correct" when it has to use it — and showing it directly
     * would put a number on screen that disagrees with what the person is paid.
     */
    @lombok.Setter
    private java.math.BigDecimal currentHourlyRate;

    /**
     * The projection contract, written out rather than generated.
     *
     * <p>Explicit because {@code departmentHead} is NOT part of it — it is
     * filled afterwards by DepartmentHeadEnricher — and an
     * {@code @AllArgsConstructor} silently included it, leaving the arity one
     * ahead of every SELECT list and failing at runtime with "Missing
     * constructor". The four query sites that build this must match the order
     * below.
     */
    public EmployeeWithBonusView(
            Long employeeId,
            String employeeNo,
            String firstName,
            String lastName,
            String fullName,
            String departmentName,
            Long departmentId,
            LocalDate employmentStartDate,
            LocalDate probationEndDate,
            String notes,
            BigDecimal transportAllowanceRsd,
            String transportAllowanceMode,
            String categoryNo,
            Long categoryId,
            String categoryName,
            BigDecimal bonusAmount,
            LocalDate bonusStart,
            String schemeCode,
            String schemeName,
            Boolean allowsPerformanceBonus,
            String mobilePhone,
            String email,
            BigDecimal hourlyRate,
            Long defaultWorkCategoryId,
            String defaultWorkCategoryName,
            String preferredLocale) {
        this.employeeId = employeeId;
        this.employeeNo = employeeNo;
        this.firstName = firstName;
        this.lastName = lastName;
        this.fullName = fullName;
        this.departmentName = departmentName;
        this.departmentId = departmentId;
        this.employmentStartDate = employmentStartDate;
        this.probationEndDate = probationEndDate;
        this.notes = notes;
        this.transportAllowanceRsd = transportAllowanceRsd;
        this.transportAllowanceMode = transportAllowanceMode;
        this.categoryNo = categoryNo;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.bonusAmount = bonusAmount;
        this.bonusStart = bonusStart;
        this.schemeCode = schemeCode;
        this.schemeName = schemeName;
        this.allowsPerformanceBonus = allowsPerformanceBonus;
        this.mobilePhone = mobilePhone;
        this.email = email;
        this.hourlyRate = hourlyRate;
        this.defaultWorkCategoryId = defaultWorkCategoryId;
        this.defaultWorkCategoryName = defaultWorkCategoryName;
        this.preferredLocale = preferredLocale;
    }
}
