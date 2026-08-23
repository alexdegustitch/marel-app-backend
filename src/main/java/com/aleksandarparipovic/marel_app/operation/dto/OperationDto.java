package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.Data;

import java.time.LocalDate;


@Data
public class OperationDto {

    private Long id;
    private Long productId;
    private String operationName;
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

}
