package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolution;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Writes a {@link WorkCategoryResolution} onto a work log as an immutable
 * historical snapshot.
 *
 * <p>One place, so creation, editing and recalculation cannot disagree about
 * what a snapshot contains. Everything written here answers the question "what
 * was this work worth, under which policy, on the day it was recorded" without
 * reading the employee's current scheme or the current rule set.
 *
 * <p>The source category is never touched.
 */
@Component
@RequiredArgsConstructor
public class WorkLogCompensationSnapshot {

    /**
     * work_logs.norm_multiplier_snapshot is NUMERIC(38,2). Scheme overrides are
     * NUMERIC(10,2) and category multipliers have at most one decimal, so this
     * never actually rounds anything away — it only makes the stored scale
     * predictable so equality comparisons in tests and reports behave.
     */
    private static final int COEFFICIENT_SCALE = 2;

    private final EntityManager entityManager;

    /**
     * Apply a resolution to a log.
     *
     * <p>Uses entity references rather than loading the rows: only the foreign
     * keys are being written, and this runs once per log in a batch.
     */
    public void apply(WorkLog log, WorkCategoryResolution resolution) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(resolution, "resolution");

        log.setCompensationScheme(reference(CompensationScheme.class, resolution.compensationSchemeId()));
        log.setWorkCodeCategorySchemeRule(
                reference(WorkCodeCategorySchemeRule.class, resolution.schemeRuleId()));

        // NULL when the scheme did not remap: the effective category IS the
        // source category, and duplicating the id would only invite the two to
        // drift apart.
        log.setSchemeEffectiveWorkCode(resolution.isCategoryRemapped()
                ? reference(WorkCodeCategory.class, resolution.effectiveCategoryId())
                : null);

        log.setNormMultiplierSnapshot(scaled(resolution.coefficient()));
    }

    /**
     * True when the log's snapshot already matches this resolution.
     *
     * <p>The recalc engine checks this before writing so an unchanged recalc
     * produces no UPDATE — which keeps the audit log free of rows recording that
     * nothing happened.
     */
    public boolean matches(WorkLog log, WorkCategoryResolution resolution) {
        return sameId(log.getCompensationScheme() == null ? null : log.getCompensationScheme().getId(),
                        resolution.compensationSchemeId())
                && sameId(log.getWorkCodeCategorySchemeRule() == null
                                ? null : log.getWorkCodeCategorySchemeRule().getId(),
                        resolution.schemeRuleId())
                && sameId(log.getSchemeEffectiveWorkCode() == null
                                ? null : log.getSchemeEffectiveWorkCode().getId(),
                        resolution.isCategoryRemapped() ? resolution.effectiveCategoryId() : null)
                && sameCoefficient(log.getNormMultiplierSnapshot(), resolution.coefficient());
    }

    /**
     * The coefficient this log should be calculated with.
     *
     * <p><b>The one place the choice is made</b>, which is what lets every
     * consumer — the recalc engine, the interval engine behind PL/PLB, the fast
     * read path — agree without any of them knowing about schemes or overrides.
     *
     * <p>Three sources, in order. A coefficient somebody TYPED wins: it is a
     * decision, and the resolved value it replaced is still on the row to be
     * shown beside it. Otherwise the resolved snapshot. It is absent only on rows
     * created before compensation schemes did, and those fall back to the source
     * category's multiplier — exactly the value they were calculated with at the
     * time.
     */
    public static BigDecimal coefficientOf(WorkLog log) {
        if (log.getNormMultiplierManual() != null) {
            return log.getNormMultiplierManual();
        }
        if (log.getNormMultiplierSnapshot() != null) {
            return log.getNormMultiplierSnapshot();
        }
        WorkCodeCategory category = log.getWorkCode();
        if (category == null || category.getNormMultiplier() == null) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(category.getNormMultiplier());
    }

    private <T> T reference(Class<T> type, Long id) {
        return id == null ? null : entityManager.getReference(type, id);
    }

    private static BigDecimal scaled(BigDecimal coefficient) {
        return coefficient == null ? null : coefficient.setScale(COEFFICIENT_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean sameId(Long a, Long b) {
        return Objects.equals(a, b);
    }

    private static boolean sameCoefficient(BigDecimal stored, BigDecimal resolved) {
        BigDecimal expected = scaled(resolved);
        if (stored == null || expected == null) {
            return stored == null && expected == null;
        }
        return stored.compareTo(expected) == 0;
    }
}
