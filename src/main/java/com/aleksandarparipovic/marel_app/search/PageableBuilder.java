package com.aleksandarparipovic.marel_app.search;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collections;
import java.util.List;

public final class PageableBuilder {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 200;

    private PageableBuilder() {
    }

    public static Pageable from(SearchRequest request) {
        int page = DEFAULT_PAGE;
        int size = DEFAULT_SIZE;

        if (request != null && request.getPagination() != null) {
            page = Math.max(DEFAULT_PAGE, request.getPagination().getPage());
            int requestedSize = request.getPagination().getSize();
            if (requestedSize > 0) {
                size = Math.min(requestedSize, MAX_PAGE_SIZE);
            }
        }

        Sort sort = toSort(request == null ? List.of() : request.getSort());
        return PageRequest.of(page, size, sort);
    }

    private static Sort toSort(List<SearchRequest.SortField> sortFields) {
        if (sortFields == null) {
            sortFields = Collections.emptyList();
        }

        if (sortFields.isEmpty()) {
            return Sort.unsorted();
        }

        List<Sort.Order> orders = sortFields.stream()
                .filter(s -> s != null && s.getField() != null && !s.getField().isBlank())
                .map(PageableBuilder::toOrder)
                .toList();

        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }

    private static Sort.Order toOrder(SearchRequest.SortField sortField) {
        Sort.Direction direction =
                sortField.getDirection() == SearchRequest.Direction.DESC
                        ? Sort.Direction.DESC
                        : Sort.Direction.ASC;

        return new Sort.Order(direction, sortField.getField());
    }
}
