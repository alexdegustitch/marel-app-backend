package com.aleksandarparipovic.marel_app.payroll_calculation;

/**
 * The calculation keys that have an implementation.
 *
 * <p>A key in {@code payroll_adjustment_categories.calculation_key} that is not
 * here makes {@link PayrollCalculatorRegistry#require} throw. That is deliberate:
 * a missing calculator must stop the payroll run, not quietly pay zero.
 */
public final class CalculationKeys {

    /** No automatic value. The line keeps whatever a user entered. */
    public static final String MANUAL = "MANUAL";

    /** Meals counted by the daily recalculation, priced from app_settings. */
    public static final String MEAL_BY_ELIGIBLE_SHIFTS = "MEAL_BY_ELIGIBLE_SHIFTS";

    /**
     * One unit per work-shift record with {@code work_minutes > 0}, priced from the
     * employee's own {@code TRANSPORT_RATE}. Renamed from
     * {@code TRANSPORT_BY_WORK_DAYS} because D3 changed what it counts.
     */
    public static final String TRANSPORT_BY_QUALIFYING_SHIFTS = "TRANSPORT_BY_QUALIFYING_SHIFTS";

    /**
     * The employee's own base bonus, if they worked the minimum hours, plus the
     * hours-tier bonus for the month.
     */
    public static final String MONTHLY_BONUS_FROM_RULES = "MONTHLY_BONUS_FROM_RULES";

    /**
     * The sum of what has already been settled this month: instalment, last
     * month's phone, and the two part-payments.
     */
    public static final String PAID_PREVIOUS_PERIOD_SUM = "PAID_PREVIOUS_PERIOD_SUM";

    /** Last month's closing balance, carried forward. */
    public static final String PREVIOUS_BALANCE_CARRIED = "PREVIOUS_BALANCE_CARRIED";

    private CalculationKeys() {
    }
}
