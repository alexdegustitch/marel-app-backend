package com.aleksandarparipovic.marel_app.employee.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class EmployeeCreateRequest {
    @NotBlank
    private String employeeNo;

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotNull
    private Long departmentId;

    /**
     * Bonus category. Required ONLY for a scheme that earns a performance bonus.
     *
     * <p>Not {@code @NotNull}: under a scheme with
     * {@code allows_performance_bonus = false} the category means nothing — the
     * scheme zeroes it — so the form disables the field and sends nothing.
     * EmployeeService applies the conditional rule, because bean validation
     * cannot see the chosen scheme.
     */
    private Long categoryId;

    /**
     * Which compensation scheme this employee opens on. MANDATORY.
     *
     * <p>Until now every new employee silently opened on STANDARD. That was a
     * guess dressed as a default: the scheme decides which work categories are
     * usable and whether a performance bonus is earned at all, so it is a payroll
     * decision and the person creating the employee is the one who should make
     * it. The business rules are explicit that there is no silent fallback.
     */
    @NotNull
    private Long compensationSchemeId;

    /**
     * Length of the probation period in days. Optional; the entity default of 30
     * applies when absent.
     */
    @Min(0)
    private Integer normGraceDays;

    /**
     * Language for documents produced FOR this employee (the payroll PDF).
     * Optional; {@code sr-Latn} applies when absent. Validated server-side
     * against the supported set, and deliberately independent of the scheme.
     */
    private String preferredLocale;

    /**
     * Optional: make this employee head of their department from the start.
     *
     * <p>Only meaningful for a scheme that earns a performance bonus — the form
     * hides it otherwise and {@code EmployeeService} refuses it, so a
     * fixed-coefficient or commercial worker cannot be made a department head by
     * a hand-written request.
     */
    private DepartmentHeadOnCreate departmentHead;

    @Data
    public static class DepartmentHeadOnCreate {
        @NotNull
        private LocalDate validFrom;
        /** Null = still in post. */
        private LocalDate validTo;
        /** Null = head across all shifts. */
        private Long shiftId;
    }

    @Min(0)
    private BigDecimal transportAllowanceRsd;

    private String transportAllowanceMode = "AUTO";

    @NotNull
    private LocalDate employmentStartDate;

    private String notes;

    private String mobilePhone;

    private String email;

    @Min(0)
    private BigDecimal hourlyRate;

    private Long defaultWorkCategoryId;

}
