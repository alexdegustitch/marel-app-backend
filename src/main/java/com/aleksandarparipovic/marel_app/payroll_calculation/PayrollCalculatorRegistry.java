package com.aleksandarparipovic.marel_app.payroll_calculation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every calculator the system knows, keyed by {@code calculation_key}.
 *
 * <p><b>An unknown key is a hard error.</b> The alternative — returning zero for a
 * key nobody implemented — is a payslip line that silently pays nothing, and
 * nothing about the result says so. A payroll run that stops is recoverable; a
 * payroll run that quietly underpays is found by the employee.
 */
@Service
@Slf4j
public class PayrollCalculatorRegistry {

    private final Map<String, PayrollComponentCalculator> byKey;

    public PayrollCalculatorRegistry(List<PayrollComponentCalculator> calculators) {
        this.byKey = calculators.stream().collect(Collectors.toMap(
                PayrollComponentCalculator::calculationKey,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Two calculators claim the key " + a.calculationKey() + ": "
                                    + a.getClass().getName() + " and " + b.getClass().getName());
                },
                TreeMap::new));
        log.info("Payroll calculators registered: {}", byKey.keySet());
    }

    public boolean knows(String calculationKey) {
        return calculationKey != null && byKey.containsKey(calculationKey);
    }

    public Set<String> knownKeys() {
        return byKey.keySet();
    }

    /**
     * @throws IllegalStateException when no calculator claims the key. The message
     *         names the known keys, because the fix is always either registering a
     *         calculator or setting that category to MANUAL.
     */
    public PayrollComponentCalculator require(String calculationKey) {
        PayrollComponentCalculator calculator = byKey.get(calculationKey);
        if (calculator == null) {
            throw new IllegalStateException(
                    "No calculator for calculation_key '" + calculationKey + "'. Registered: "
                            + byKey.keySet() + ". Register one, or set the category to MANUAL.");
        }
        return calculator;
    }
}
