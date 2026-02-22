package com.aleksandarparipovic.marel_app.product.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProductWithOperationCountRow {

    private Long productId;
    private String productName;
    private String productCode;
    private String description;
    private Boolean active;
    private Long operationCount;
}
