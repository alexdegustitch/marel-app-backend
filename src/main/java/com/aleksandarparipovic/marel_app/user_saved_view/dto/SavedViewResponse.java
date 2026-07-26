package com.aleksandarparipovic.marel_app.user_saved_view.dto;


import java.time.OffsetDateTime;

public record SavedViewResponse(
        Long id,
        String viewKey,
        String name,
        java.util.Map<String, Object> filters,
        java.util.List<Object> sorting,
        java.util.List<Object> columns,
        boolean isDefault,
        boolean archived,
        OffsetDateTime createdAt
) {
}
