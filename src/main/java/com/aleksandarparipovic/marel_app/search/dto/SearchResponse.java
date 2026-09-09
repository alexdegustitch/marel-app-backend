package com.aleksandarparipovic.marel_app.search.dto;

import java.util.List;

/** Wrapper so the body is {@code { "results": [...] }} rather than a bare array. */
public record SearchResponse(List<SearchResult> results) {
}
