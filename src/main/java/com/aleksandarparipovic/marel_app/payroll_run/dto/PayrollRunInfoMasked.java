package com.aleksandarparipovic.marel_app.payroll_run.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payroll row as reported to somebody who may not see what it is worth.
 *
 * <p>The repository returns {@link PayrollRunInfoDto} as an interface
 * projection, which cannot be edited — so the masked row is a concrete
 * implementation of the same contract, with the amounts left null and the
 * status already collapsed. The client receives the same shape either way and
 * has no branch to get wrong.
 */
@Getter
@AllArgsConstructor
public class PayrollRunInfoMasked implements PayrollRunInfoDto {

    private final Long id;
    private final Long employeeId;
    private final String employeeName;
    private final String employeeNo;
    private final String employeeDepartment;
    private final String status;
    private final BigDecimal totalNetEarnings;
    private final BigDecimal netPayableAmount;
    private final Long monthlyReportId;
    private final Instant updatedAt;

    /** The same row with both amounts withheld and the status made visible. */
    public static PayrollRunInfoMasked withoutAmounts(PayrollRunInfoDto row, String visibleStatus) {
        return new PayrollRunInfoMasked(
                row.getId(),
                row.getEmployeeId(),
                row.getEmployeeName(),
                row.getEmployeeNo(),
                row.getEmployeeDepartment(),
                visibleStatus,
                null,
                null,
                row.getMonthlyReportId(),
                row.getUpdatedAt());
    }
}
