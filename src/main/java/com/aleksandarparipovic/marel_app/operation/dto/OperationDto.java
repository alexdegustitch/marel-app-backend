package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.Data;

import java.time.LocalDate;


@Data
public class OperationDto {

    private Long id;
    private Long productId;
    private String operationName;

    /** The operation's own description — read by the add-operations drawer so a
     * copy carries the source's definition, not only its name. */
    private String description;

    private Integer minNorm;
    private Integer maxNorm;
    private Boolean normRequired;
    private Integer unitsPerProduct;
    private LocalDate normDate;

    /**
     * The norm in force was entered without a date on purpose. The screens read
     * "privremena" where the date would be, rather than showing nothing and
     * leaving the reader to guess whether somebody forgot.
     */
    private Boolean normTemporary;

    /**
     * Which work category the operation belongs to. The label is resolved by the
     * caller from the shared category options (which carry translations), so the
     * name is never duplicated — and never drifts — across read paths.
     */
    private Long workCodeCategoryId;

    // ── V77: binding, group, step ────────────────────────────────────────────

    /** Step within the product; equal numbers run in parallel, null = unplaced. */
    private Integer sortOrder;

    /** The group of interchangeable machine-operations, when it is in one. */
    private Long operationGroupId;
    private String operationGroupName;
    /** The member manufacturing time preselects and razrada names. */
    private Boolean groupDefault;

    /**
     * The binding: the attribute value this operation exists for. All four null
     * = the operation applies to every variant of its product (every pre-V77
     * operation). The karton badge and the "važi za" chip read these.
     */
    private Long bindingAttributeId;
    private String bindingAttributeName;
    private Long bindingOptionId;
    private String bindingOptionValue;
}
