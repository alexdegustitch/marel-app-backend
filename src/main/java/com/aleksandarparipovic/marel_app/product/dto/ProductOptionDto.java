package com.aleksandarparipovic.marel_app.product.dto;

public record ProductOptionDto (
    Long id,
    String name,
    /** An INTERNI proizvod: the order forms' pickers leave it out, the karton keeps it. */
    Boolean isVirtual
){}
