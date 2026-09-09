package com.aleksandarparipovic.marel_app.assistant.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A STRUCTURED, read-only monthly report for one employee's karton, assembled
 * DETERMINISTICALLY from real DB figures (no model call). Every field is either
 * sourced from an existing repository/query or left null/empty — nothing here is
 * estimated or invented. Deliberately carries no payroll (dinar) figures: this
 * report is about work and norm, not pay.
 *
 * <p>When there is no record/report for the given id the endpoint still answers
 * 200, with {@link #hasData()} false and the collections empty.
 */
public record EmployeeMonthReport(
        boolean hasData,

        String employeeName,
        String employeeNo,
        String department,

        int month,
        int year,

        /** Approved performance rate (%), falling back to the raw rate; null when unknown. */
        BigDecimal performanceRate,
        /** The SAME employee's performance rate (%) for the previous month, or null. */
        BigDecimal prevPerformanceRate,

        BigDecimal workedHours,
        BigDecimal shiftHours,
        BigDecimal absencePaidHours,
        BigDecimal absenceUnpaidHours,
        BigDecimal sickHours,

        int quantity,
        int scrap,

        Integer totalShifts,
        Integer goodShifts,
        Integer weakShifts,
        /** Null: no reliable existing signal for missing/incomplete shifts. */
        Integer missingOrIncompleteShifts,

        List<OperationStat> topOperations,
        List<OperationStat> weakOperations,

        List<NameCount> shiftTypes,
        /** Empty: no operation category/type concept is sourceable from work logs. */
        List<NameCount> operationTypes
) {

    /** One operation's standing for this employee this month, with its product
     *  (name + catalog code) and the ids needed to link to it. */
    public record OperationStat(
            long operationId, String name,
            long productId, String productName, String productCode,
            BigDecimal rate, int quantity) {
    }

    /** A labelled count — a shift type worked, or an operation type. */
    public record NameCount(String name, int count) {
    }

    /** The answer when the record or its monthly report does not exist. */
    public static EmployeeMonthReport empty() {
        return new EmployeeMonthReport(
                false,
                null, null, null,
                0, 0,
                null, null,
                null, null, null, null, null,
                0, 0,
                null, null, null, null,
                List.of(), List.of(),
                List.of(), List.of());
    }
}
