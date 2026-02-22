package com.aleksandarparipovic.marel_app.operation.repository;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

public interface OperationRepositoryCustom {
    <T> Page<T> searchWithProjection(
            Specification<Operation> spec,
            Pageable pageable,
            Class<T> projectionType
    );

    Page<OperationWithProductInfoRow> searchNative(
            SearchRequest request,
            Pageable pageable
    );

}
