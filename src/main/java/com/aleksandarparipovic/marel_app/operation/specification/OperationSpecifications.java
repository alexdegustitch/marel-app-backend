package com.aleksandarparipovic.marel_app.operation.specification;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationFieldMapper;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.search.SearchSpecification;
import org.springframework.data.jpa.domain.Specification;

public final class OperationSpecifications {

    private static final OperationFieldMapper FIELD_MAPPER = new OperationFieldMapper();

    private OperationSpecifications(){}

    public static Specification<Operation> fromSearchRequest(SearchRequest request){
        return Specification.where(notArchived())
                .and(new SearchSpecification<>(request, FIELD_MAPPER));
    }

    public static Specification<Operation> notArchived(){
        return ((root, query, criteriaBuilder) -> criteriaBuilder.isNull(root.get("archivedAt")));
    }
}
