package com.aleksandarparipovic.marel_app.product.specification;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.ProductFieldMapper;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.search.SearchSpecification;
import org.springframework.data.jpa.domain.Specification;

public final class ProductSpecifications {

    private static final ProductFieldMapper FIELD_MAPPER = new ProductFieldMapper();

    private ProductSpecifications() {
    }

    public static Specification<Product> fromSearchRequest(SearchRequest request) {
        return Specification.where(notArchived())
                .and(new SearchSpecification<>(request, FIELD_MAPPER));
    }

    public static Specification<Product> notArchived() {
        return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
    }
}
