package com.aleksandarparipovic.marel_app.product.specification;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.ProductFieldMapper;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.search.SearchSpecification;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public final class ProductSpecifications {

    /**
     * The virtual filter the board's "Bez operacija" tile sends. It is not a
     * column, so the generic field mapper cannot answer it — it is lifted out
     * of the request here and translated into an EXISTS predicate instead.
     */
    private static final String HAS_OPERATIONS_FIELD = "hasOperations";

    private static final ProductFieldMapper FIELD_MAPPER = new ProductFieldMapper();

    private ProductSpecifications() {
    }

    public static Specification<Product> fromSearchRequest(SearchRequest request) {
        Specification<Product> spec = Specification.where(notArchived());

        Boolean hasOperations = extractHasOperations(request);
        if (hasOperations != null) {
            spec = spec.and(hasLiveOperations(hasOperations));
        }

        return spec.and(new SearchSpecification<>(request, FIELD_MAPPER));
    }

    public static Specification<Product> notArchived() {
        return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
    }

    /**
     * Whether the product has any live operation. An EXISTS subquery rather
     * than a join predicate, so it works identically in the projection query
     * (which already left-joins operations for the count) and the count query
     * (which joins nothing).
     */
    private static Specification<Product> hasLiveOperations(boolean has) {
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            var op = sub.from(Operation.class);
            sub.select(op.get("id")).where(
                    cb.equal(op.get("product"), root),
                    cb.isNull(op.get("archivedAt"))
            );
            return has ? cb.exists(sub) : cb.not(cb.exists(sub));
        };
    }

    /**
     * Pulls the virtual filter out of the request so the generic
     * SearchSpecification never sees a field it would reject. The request is
     * a per-call DTO, so trimming its filter list in place is safe.
     */
    private static Boolean extractHasOperations(SearchRequest request) {
        if (request == null || request.getFilters() == null) return null;

        Boolean value = null;
        List<SearchRequest.FilterField> remaining = new java.util.ArrayList<>();
        for (SearchRequest.FilterField filter : request.getFilters()) {
            if (filter != null && HAS_OPERATIONS_FIELD.equals(filter.getField())) {
                value = Boolean.parseBoolean(String.valueOf(filter.getValue()));
            } else {
                remaining.add(filter);
            }
        }
        request.setFilters(remaining);
        return value;
    }
}
