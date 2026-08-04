package com.aleksandarparipovic.marel_app.employee_payroll_value;

/**
 * The definition codes seeded by {@code 2026-08-01-01-employee-payroll-value-definitions.sql}.
 *
 * <p>Definitions are resolved by code, never by a hard-coded id — ids differ
 * between environments and a calculator that looks one up by number is a
 * data-corruption bug waiting for the first fresh database.
 *
 * <p>Not an enum over every definition: an administrator may add one, and the
 * lookup treats them all alike. Only the codes that calculator code references
 * are named here.
 */
public final class EmployeePayrollValueCodes {

    /** Prices work categories. A calculation input, not a payslip line. */
    public static final String HOURLY_RATE = "HOURLY_RATE";

    /**
     * A FIXED MONTHLY transport amount, for the employees who are paid one.
     *
     * <p>Not a per-arrival rate. Having this value is what puts an employee on the
     * fixed mode: they are paid it whole, whatever they worked. Everyone else is
     * paid per day, from the company rate in {@code app_settings}.
     */
    public static final String TRANSPORT_FIXED_MONTHLY = "TRANSPORT_FIXED_MONTHLY";

    /**
     * BOOLEAN. TRUE means the employee is paid transport for each day worked, at
     * the company rate in force on the month's last day.
     *
     * <p>Having this in force is what puts an employee on the per-day mode — the
     * same sentence that governs {@link #TRANSPORT_FIXED_MONTHLY}, so there is no
     * separate flag to fall out of step with. An employee with neither is paid no
     * transport, which is how a month before the entitlement began differs from
     * one after it (OPEN-15).
     */
    public static final String TRANSPORT_PER_DAY = "TRANSPORT_PER_DAY";

    public static final String FIXED_LD_AMOUNT = "FIXED_LD_AMOUNT";

    private EmployeePayrollValueCodes() {
    }
}
