package com.aleksandarparipovic.marel_app.payroll_maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Prints the configuration report once, at startup, when explicitly asked.
 *
 * <p>Same reasoning as {@link PayrollRecalculationRunner}: the endpoint is the
 * ordinary way in, but reading it over HTTP means somebody authenticating first,
 * and an operator's password is not a thing to pass around to look at a report.
 * The flag needs nothing but the flag.
 *
 * <p>OFF UNLESS SET, and it never fails startup — it reports, it does not refuse.
 * That is the whole design: the calculation is what must be strict, and it is.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.payroll.report-configuration-on-startup=true
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "app.payroll.report-configuration-on-startup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PayrollConfigurationReportRunner implements ApplicationRunner {

    private final PayrollConfigurationValidationService validationService;

    @Override
    public void run(ApplicationArguments args) {
        PayrollConfigurationReport report = validationService.validate();

        log.info("=== PAYROLL CONFIGURATION: {} blocking, {} warning ===",
                report.blocking(), report.warnings());
        report.findings().forEach(f ->
                log.info("=== {} · {} · {} — {}", f.severity(), f.code(), f.subject(), f.message()));

        if (report.isPayrollRunnable()) {
            log.info("=== Nothing blocks a payroll month from being calculated. ===");
        }
    }
}
