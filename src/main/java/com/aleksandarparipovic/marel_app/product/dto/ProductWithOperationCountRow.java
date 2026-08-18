package com.aleksandarparipovic.marel_app.product.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    // Serialized as `isActive`: that is the name the products table reads and
    // filters by. The constructor (JPA projection) keeps its positional order.
    @JsonProperty("isActive")
    private Boolean active;
    private Long operationCount;
}
