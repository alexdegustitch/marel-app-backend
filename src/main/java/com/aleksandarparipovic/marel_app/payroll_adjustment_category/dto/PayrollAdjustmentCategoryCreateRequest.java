package com.aleksandarparipovic.marel_app.payroll_adjustment_category.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class PayrollAdjustmentCategoryCreateRequest {
    @NotBlank
    private String code;
    private String name;
    /**
     * The English translation of {@link #name}, stored in
     * payroll_adjustment_category_translations — never as a column on this
     * category and never copied onto a payroll adjustment.
     *
     * <p>{@code null} leaves any existing translation untouched, so a client that
     * does not know about translations can still edit the other fields. Blank
     * removes it, and the name falls back to {@link #name}.
     */
    private String nameEn;
    private String sectionCode;
    private Integer sectionOrder;
    private Integer sortOrder;
    private String impactCode;
    private String inputType;
    private Boolean isManual;
    private Boolean allowOverride;
    private String overrideTarget;
    private Boolean allowNegative;
    private Boolean isActive;
    private Boolean visibleInUi;
    private Boolean visibleInPdf;
    private String calculationKey;
}
