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

    // Catalogue fields the list shows: the catalogue number that identifies the
    // article, the subtype the product name is qualified by, and the type/family
    // it is filed under. All null for an uncategorised product. The constructor
    // (JPA projection) keeps these in positional order after operationCount.
    private String catalogNumber;
    private String subtype;
    private String productTypeName;
    private String familyName;
}
