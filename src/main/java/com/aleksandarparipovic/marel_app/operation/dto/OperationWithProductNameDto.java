package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@AllArgsConstructor
@Data
public class OperationWithProductNameDto {
    private Long id;
    private String operationName;
    private Integer minNorm;
    private Integer maxNorm;
    private Boolean normRequired;
    private LocalDate normDate;
    private Integer unitsPerProduct;
    private Long productId;
    private String productName;
    /** The product's display name, when it carries one — what its own page titles itself. */
    private String productDisplayName;
    /** The product's catalogue number ("kataloški broj"); null when it carries none. */
    private String catalogNumber;
    private Long workCodeCategoryId;
    // The norm in force was entered without a date on purpose ("privremena").
    // Lets the edit form show the box already ticked.
    private Boolean normTemporary;
}
