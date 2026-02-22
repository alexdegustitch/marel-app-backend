package com.aleksandarparipovic.marel_app.product.dto;

public record ProductBaseRow(
        Long productId,
        String productName,
        String productCode,
        String description,
        Boolean active
) {}
