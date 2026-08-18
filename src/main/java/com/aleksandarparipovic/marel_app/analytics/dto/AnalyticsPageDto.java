package com.aleksandarparipovic.marel_app.analytics.dto;

import java.util.List;

/**
 * One page of an analytics report.
 *
 * <p>Deliberately not Spring's {@code PageImpl}: that class re-derives the total from the
 * content whenever the page looks like the last one ({@code offset + size > total}), which is
 * right when a page holds N rows out of N total and wrong here — a banded page holds a page of
 * PRODUCTS whose content is their operations, so the count of rows and the count of the things
 * being paged are different numbers on purpose.
 *
 * @param content        the rows of this page
 * @param page           what is being paged, and how much of it there is
 */
public record AnalyticsPageDto<T>(List<T> content, PageMeta page) {

    /**
     * @param size          page size, in whatever unit the report pages by (rows, or products)
     * @param number        zero-based page index
     * @param totalElements how many of that unit the filters left standing
     * @param totalPages    pages at this size
     */
    public record PageMeta(int size, int number, long totalElements, int totalPages) {}

    public static <T> AnalyticsPageDto<T> of(List<T> content, int size, int number, long totalElements) {
        int totalPages = size <= 0 ? 1 : (int) Math.max(1, Math.ceil((double) totalElements / size));
        return new AnalyticsPageDto<>(content, new PageMeta(size, number, totalElements, totalPages));
    }
}
