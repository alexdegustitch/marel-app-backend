package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A hand-made SETTLEMENTS line: what it is called on this payslip and what it
 * is worth.
 */
@Getter
@Setter
public class CustomAdjustmentCreateRequest {

    /**
     * The SETTLEMENTS category the line belongs to. Null — the normal case —
     * means the CUSTOM carrier: the line is whatever its name says it is.
     * Naming a category is kept for compatibility, not offered by the UI.
     */
    private Long categoryId;

    /**
     * What the line says on the payslip. Optional everywhere — empty, or
     * exactly the category's own name, stores nothing, and the line then shows
     * the category's name, translations included, like any system row.
     */
    private String displayName;

    /** The figure. Null means 0,00 — a line somebody will fill in. */
    private BigDecimal amount;

    private String note;
}
