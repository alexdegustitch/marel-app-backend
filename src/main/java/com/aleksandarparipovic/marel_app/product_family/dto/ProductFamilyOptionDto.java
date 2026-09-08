package com.aleksandarparipovic.marel_app.product_family.dto;

/**
 * A family as a picker offers it: id and name, in list order.
 */
public record ProductFamilyOptionDto(
        Long id,
        String name
) {}
