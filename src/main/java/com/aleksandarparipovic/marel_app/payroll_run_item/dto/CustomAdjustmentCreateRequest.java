package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A hand-made SETTLEMENTS line: which category it is, what it is called on this
 * payslip, and what it is worth.
 */
@Getter
@Setter
public class CustomAdjustmentCreateRequest {

    /** The SETTLEMENTS category the line belongs to. */
    @NotNull
    private Long categoryId;

    /**
     * What the line says on the payslip. Empty, or exactly the category's own
     * name, stores nothing — the line then shows the category name, translations
     * included, like any system row.
     */
    private String displayName;

    /** The figure. Null means 0,00 — a line somebody will fill in. */
    private BigDecimal amount;

    private String note;
}
