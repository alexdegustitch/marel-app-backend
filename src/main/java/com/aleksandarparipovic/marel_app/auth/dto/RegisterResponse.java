package com.aleksandarparipovic.marel_app.auth.dto;

public record RegisterResponse(
        Long userId,
        String username,
        String message
) {
}
