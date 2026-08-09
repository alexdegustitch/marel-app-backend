package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.work_category_resolution.EffectiveComponentConfig;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
public class PayrollAdjustmentDetailDto {

    private final Long id;
    private final Long payrollAdjustmentCategoryId;

    // ── Category definition ──────────────────────────────────────────────────
    private final String categoryCode;
    /** The master (default-locale) name. Unchanged. */
    private final String categoryName;
    /**
     * The name to display, in the requested locale, falling back to
     * {@link #categoryName}.
     *
     * <p>Always resolved through payroll_adjustment_category_id. The adjustment
     * row itself stores no name, translated or otherwise.
     */
    private final String categoryDisplayName;
    private final String sectionCode;
    private final Integer sectionOrder;
    private final Integer sortOrder;
    private final String impactCode;
    private final String inputType;
    private final Boolean isManual;
    private final Boolean allowOverride;
    private final String overrideTarget;
    private final Boolean allowNegative;
    private final Boolean showName;

    // ── Effective configuration: the SCHEME's answer, not the category's ─────
    //
    // Every field below is already COALESCE(scheme rule, category). The client
    // renders from these and asks nothing else — that is what removes the
    // `if (employee.isForeigner())` branches from the UI, because the difference
    // between a standard, foreign, commercial or seasonal employee arrives here as
    // ordinary data.

    /** Whether the line is shown at all. False for a line the scheme excludes. */
    private final Boolean visibleInUi;
    private final Boolean visibleInPdf;
    /** Show the line even when the amount is 0 — how a commercial bonus stays visible. */
    private final Boolean showWhenZero;
    /** NONE, AMOUNT, UNIT_AMOUNT, QUANTITY or CORRECTION — which input may be edited. */
    private final String editableInput;
    /** Whether the final amount may be typed in, bypassing the formula. Needs a reason. */
    private final Boolean allowTotalOverride;
    /** Whether the item cannot be locked until somebody fills this line in. */
    private final Boolean requiredManualInput;
    /** INHERIT, ZERO or MANUAL. ZERO is why a line can be shown and always nothing. */
    private final String calculationMode;

    // ── Adjustment values ────────────────────────────────────────────────────
    private final BigDecimal systemQuantity;
    private final BigDecimal quantity;
    private final BigDecimal systemUnitAmount;
    private final BigDecimal unitAmount;
    private final BigDecimal systemAmount;
    private final BigDecimal amount;
    /** A delta added to the system figure — the bonus correction. Not an override. */
    /** What the rules produced, beside {@link #correctionAmount}, which is what applies. */
    private BigDecimal systemCorrectionAmount;

    private final BigDecimal correctionAmount;
    /** The final amount was typed in and the formula bypassed. */
    private final Boolean isOverridden;
    private final String overrideReason;
    /** TRUE once a user entered a value, INCLUDING an explicit 0. */
    private final Boolean hasManualInput;
    /** CALCULATED, PENDING_INPUT, MANUAL, OVERRIDDEN or ERROR. */
    private final String status;
    /**
     * What the calculator was given, and why it produced what it did.
     *
     * <p>Carries the reason for a zero. Without it a line at 0,00 on a payslip
     * cannot be told apart from a fault, and somebody has to be able to answer
     * "why did this person get no transport this month".
     */
    private final java.util.Map<String, Object> calculationInputs;
    private final String note;
    private final Boolean isApplied;

    private final Long createdById;
    private final Long editedById;
    private final OffsetDateTime editedAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    /** Default locale, and no scheme configuration resolved. */
    public PayrollAdjustmentDetailDto(PayrollAdjustment a) {
        this(a, java.util.Map.of(), null);
    }

    /**
     * @param translations category id -> translated name for one locale, loaded
     *                     once by the caller.
     * @param config       the scheme's fully resolved answer for this line. When
     *                     {@code null} the category's own defaults are used — that
     *                     path exists only for callers with no payroll period in
     *                     hand, and it cannot express an exclusion.
     */
    public PayrollAdjustmentDetailDto(PayrollAdjustment a,
                                      java.util.Map<Long, String> translations,
                                      EffectiveComponentConfig config) {
        this(a, translations, config, true);
    }

    /**
     * @param editableByReader whether THIS reader may change the line, which is a
     *                         different question from whether the line is editable
     *                         at all. False lowers the line to read-only before it
     *                         is sent, so the screen draws a value instead of an
     *                         input. The server refuses the write regardless; this
     *                         is so nobody types into a field that was never going
     *                         to save.
     */
    public PayrollAdjustmentDetailDto(PayrollAdjustment a,
                                      java.util.Map<Long, String> translations,
                                      EffectiveComponentConfig config,
                                      boolean editableByReader) {
        PayrollAdjustmentCategory cat = a.getPayrollAdjustmentCategory();

        this.id = a.getId();
        this.payrollAdjustmentCategoryId = cat.getId();

        this.categoryCode = cat.getCode();
        this.categoryName = cat.getName();
        String translated = translations == null ? null : translations.get(cat.getId());
        this.categoryDisplayName =
                translated != null && !translated.isBlank() ? translated : this.categoryName;
        this.sectionCode = cat.getSectionCode();
        this.sectionOrder = cat.getSectionOrder();
        this.sortOrder = cat.getSortOrder();
        this.impactCode = cat.getImpactCode();
        this.inputType = cat.getInputType();
        this.isManual = cat.getIsManual();
        this.allowOverride = cat.getAllowOverride();
        this.overrideTarget = cat.getOverrideTarget();
        this.allowNegative = cat.getAllowNegative();
        this.showName = cat.getShowName();

        this.visibleInUi = config != null ? config.visibleInUi() : cat.getVisibleInUi();
        this.visibleInPdf = config != null ? config.visibleInPdf() : cat.getVisibleInPdf();
        this.showWhenZero = config != null ? config.showWhenZero() : cat.getShowWhenZero();
        this.editableInput = !editableByReader
                ? "NONE"
                : (config != null ? config.editableInput() : cat.getEditableInput());
        this.allowTotalOverride = editableByReader
                && Boolean.TRUE.equals(
                        config != null ? config.allowTotalOverride() : cat.getAllowTotalOverride());
        this.requiredManualInput =
                config != null ? config.requiredManualInput() : cat.getRequiredManualInput();
        this.calculationMode = config != null ? config.calculationMode() : "INHERIT";

        this.systemQuantity = a.getSystemQuantity();
        this.quantity = a.getQuantity();
        this.systemUnitAmount = a.getSystemUnitAmount();
        this.unitAmount = a.getUnitAmount();
        this.systemAmount = a.getSystemAmount();
        this.amount = a.getAmount();
        this.correctionAmount = a.getCorrectionAmount();
        this.systemCorrectionAmount = a.getSystemCorrectionAmount();
        this.isOverridden = a.getIsOverridden();
        this.overrideReason = a.getOverrideReason();
        this.hasManualInput = a.getHasManualInput();
        this.status = a.getStatus();
        this.calculationInputs = a.getCalculationInputs();
        this.note = a.getNote();
        this.isApplied = a.getIsApplied();

        this.createdById = a.getCreatedBy() != null ? a.getCreatedBy().getId() : null;
        this.editedById = a.getEditedBy() != null ? a.getEditedBy().getId() : null;
        this.editedAt = a.getEditedAt();
        this.createdAt = a.getCreatedAt();
        this.updatedAt = a.getUpdatedAt();
    }
}
