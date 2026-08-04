package com.aleksandarparipovic.marel_app.payroll_maintenance;

import java.util.List;

/**
 * What is wrong with the payroll configuration right now.
 *
 * @param blocking how many findings will stop or misprice a payroll today
 * @param warnings how many are gaps that have not bitten yet
 * @param findings all of them, worst first
 */
public record PayrollConfigurationReport(int blocking, int warnings,
                                         List<PayrollConfigurationFinding> findings) {

    /** True when nothing would stop a payroll month from being calculated. */
    public boolean isPayrollRunnable() {
        return blocking == 0;
    }
}
