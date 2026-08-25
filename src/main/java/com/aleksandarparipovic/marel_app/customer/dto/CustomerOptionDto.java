package com.aleksandarparipovic.marel_app.customer.dto;

/**
 * A customer as a picker offers them.
 *
 * <p>Carries the code as well as the name, because two customers can honestly
 * share a name — a group and its subsidiary — and the code is what tells them
 * apart in a dropdown that has room for nothing else.
 */
public record CustomerOptionDto(
        Long id,
        String name,
        String code
) {}
