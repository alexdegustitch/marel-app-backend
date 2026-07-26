package com.aleksandarparipovic.marel_app.production_order.specification;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderFieldMapper;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.search.SearchSpecification;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProductionOrderSpecifications {

    private static final ProductionOrderFieldMapper FIELD_MAPPER = new ProductionOrderFieldMapper();
    private static final String PRIORITY_FLAGS_FIELD = "priorityFlags";

    private ProductionOrderSpecifications() {
    }

    public static Specification<ProductionOrder> fromSearchRequest(SearchRequest request) {
        Specification<ProductionOrder> specification = Specification.where(notArchived());

        List<String> priorityFlags = extractPriorityFlags(request);
        if (!priorityFlags.isEmpty()) {
            specification = specification.and(priorityFlagsSpecification(priorityFlags));
        }

        return specification.and(new SearchSpecification<>(request, FIELD_MAPPER));
    }

    public static Specification<ProductionOrder> notArchived() {
        return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
    }

    private static List<String> extractPriorityFlags(SearchRequest request) {
        if (request == null || request.getFilters() == null) {
            return List.of();
        }

        List<String> flags = List.of();
        List<SearchRequest.FilterField> remaining = new ArrayList<>();

        for (SearchRequest.FilterField filter : request.getFilters()) {
            if (filter != null && PRIORITY_FLAGS_FIELD.equals(filter.getField())) {
                flags = toStringList(filter.getValue());
            } else {
                remaining.add(filter);
            }
        }

        request.setFilters(remaining);
        return flags;
    }

    private static List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String s) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
        }
        return List.of();
    }

    private static Specification<ProductionOrder> priorityFlagsSpecification(List<String> flags) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (String flag : flags) {
                switch (flag) {
                    case "HIGH_PRIORITY" -> predicates.add(cb.isTrue(root.get("isHighPriority")));
                    case "ANNOUNCED" -> predicates.add(cb.isTrue(root.get("isAnnounced")));
                    case "SUCCESSIVE_DELIVERIES" -> predicates.add(cb.isTrue(root.get("hasSuccessiveDeliveries")));
                    default -> throw new IllegalArgumentException("Unknown priority flag: " + flag);
                }
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.or(predicates.toArray(new Predicate[0]));
        };
    }
}
