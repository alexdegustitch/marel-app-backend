package com.aleksandarparipovic.marel_app.search.dto;

/**
 * One hit in the unified command-palette search.
 *
 * <p>Deliberately flat and presentation-ready: the client renders {@code title}
 * and {@code subtitle} as-is and navigates to {@code url}. {@code type} lets the
 * palette group and icon the hit; {@code id} is the entity's own id.
 */
public record SearchResult(
        String type,
        long id,
        String title,
        String subtitle,
        String url
) {
}
