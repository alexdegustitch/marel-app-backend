package com.aleksandarparipovic.marel_app.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductCreateRequest {

    @NotBlank(message = "Product name is required")
    @Size(max = 255, message = "Product name is too long")
    private String productName;

    @Size(max = 100, message = "Product code is too long")
    private String productCode;

    @Size(max = 1000, message = "Description is too long")
    private String description;
}

