package com.aleksandarparipovic.marel_app.product.repository;

import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

public interface ProductRepositoryCustom {
    <T> Page<T> searchWithProjection(
            Specification<Product> specification,
            Pageable pageable,
            Class<T> projectionType
    );
}