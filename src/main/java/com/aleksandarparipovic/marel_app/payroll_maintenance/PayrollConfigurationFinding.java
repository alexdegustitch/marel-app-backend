package com.aleksandarparipovic.marel_app.payroll_maintenance;

/**
 * One thing wrong with the payroll configuration, in words an administrator can
 * act on.
 *
 * @param severity BLOCKING when payroll will refuse or misprice because of it;
 *                 WARNING when it is a gap somebody should close before it
 *                 becomes one
 * @param code     stable identifier for the KIND of problem, so a client can
 *                 group or link without parsing prose
 * @param subject  what it is about — "STANDARD × MEAL_ALLOWANCE", an employee's
 *                 name and id — so the reader knows where to go
 * @param message  what is wrong and what it will cause, in Serbian, because the
 *                 people who fix these read the admin screens
 */
public record PayrollConfigurationFinding(
        Severity severity,
        String code,
        String subject,
        String message
) {
    public enum Severity { BLOCKING, WARNING }

    public static PayrollConfigurationFinding blocking(String code, String subject, String message) {
        return new PayrollConfigurationFinding(Severity.BLOCKING, code, subject, message);
    }

    public static PayrollConfigurationFinding warning(String code, String subject, String message) {
        return new PayrollConfigurationFinding(Severity.WARNING, code, subject, message);
    }
}
