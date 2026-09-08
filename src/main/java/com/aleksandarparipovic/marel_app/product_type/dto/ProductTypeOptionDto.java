package com.aleksandarparipovic.marel_app.product_type.dto;

/**
 * A type as a picker offers it. Carries the code and the family id, because two
 * families can hold a type of the same name and the code (with its family) is
 * what tells them apart.
 */
public record ProductTypeOptionDto(
        Long id,
        Long familyId,
        String name,
        String code
) {}
