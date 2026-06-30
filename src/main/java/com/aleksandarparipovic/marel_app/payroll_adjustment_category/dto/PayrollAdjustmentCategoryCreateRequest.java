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
