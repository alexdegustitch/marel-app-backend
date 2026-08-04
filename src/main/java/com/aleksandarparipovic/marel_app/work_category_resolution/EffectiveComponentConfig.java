package com.aleksandarparipovic.marel_app.work_category_resolution;

/**
 * What one scheme says about one payroll line, after inheritance is resolved.
 *
 * <p>Every field here is already {@code COALESCE(rule, category)}. Callers get an
 * answer, not a pair of half-answers to combine — the merge happens once, where
 * the rule and the category are both in hand, instead of at each of the four or
 * five places that ask.
 *
 * @param allowed         whether the line exists for this employee at all
 * @param calculationMode INHERIT, ZERO or MANUAL. ZERO is the difference between
 *                        "excluded" and "shown, and always nothing"
 * @param calculationKey  the calculator to run; {@code null} once the mode is not
 *                        INHERIT
 */
public record EffectiveComponentConfig(
        Long categoryId,
        String categoryCode,
        boolean allowed,
        String calculationMode,
        String calculationKey,
        boolean visibleInUi,
        boolean visibleInPdf,
        boolean showWhenZero,
        String editableInput,
        boolean allowTotalOverride,
        boolean requiredManualInput
) {
    public static final String MODE_INHERIT = "INHERIT";
    public static final String MODE_ZERO = "ZERO";
    public static final String MODE_MANUAL = "MANUAL";

    /** True when the scheme says this line exists but must never be calculated. */
    public boolean isForcedZero() {
        return MODE_ZERO.equals(calculationMode);
    }

    /** The calculator to run, or {@code null} when the scheme does not want one. */
    public String effectiveCalculationKey() {
        return MODE_INHERIT.equals(calculationMode) ? calculationKey : null;
    }
}
