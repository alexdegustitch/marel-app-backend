package com.aleksandarparipovic.marel_app.employee.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class EmployeePatchRequest {

    private String employeeNo;
    private String fullName;
    private Long departmentId;
    private Long categoryId;
    private Boolean foreigner;
    private BigDecimal transportAllowanceRsd;
    private String transportAllowanceMode;
    private LocalDate employmentStartDate;
    private LocalDate employmentEndDate;
    private Boolean active;
    private Integer normGraceDays;
    private String notes;
    private String mobilePhone;
    private BigDecimal hourlyRate;
    /**
     * The date the new hourly rate starts to apply. Optional; when absent the
     * rate applies from the FIRST OF THE CURRENT MONTH.
     *
     * <p>That default is not arbitrary. Payroll prices a month at its start date,
     * so a rate recorded from mid-month would not reach the month being
     * calculated and the correction would appear to do nothing until the next
     * one. Supplying the field explicitly is how a genuinely older start —
     * "this was actually their rate from January 2025" — is recorded.
     */
    private LocalDate hourlyRateEffectiveFrom;
    private Long defaultWorkCategoryId;
    private Boolean worksInCommercial;
    /**
     * Language for documents produced FOR this employee (the payroll PDF).
     * Independent of {@code foreigner} and of the compensation scheme.
     */
    private String preferredLocale;
}
