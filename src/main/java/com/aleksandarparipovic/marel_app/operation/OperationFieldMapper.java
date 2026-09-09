package com.aleksandarparipovic.marel_app.operation;

import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.search.EntityFieldMapper;
import com.aleksandarparipovic.marel_app.search.JoinManager;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class OperationFieldMapper implements EntityFieldMapper<Operation> {

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
    private Long operationId;
    private Long productId;
    private String operationName;
    private String productName;
    private Integer minNorm;
    private Integer maxNorm;
    private Integer unitsPerProduct;
    private LocalDate normDate;
    private static final Map<String, TriFunction<Root<Operation>, CriteriaBuilder, JoinManager<Operation>, Path<?>>> FIELD_MAP =
            Map.ofEntries(
                    Map.entry("operationId", (root, cb,jm)->root.get("id")),
                    Map.entry("operationName", (root, cb,jm)->root.get("opName")),
                    Map.entry("minNorm", (root, cb,jm)->root.get("minNorm")),
                    Map.entry("maxNorm", (root, cb,jm)->root.get("maxNorm")),
                    Map.entry("normRequired", (root, cb,jm)->root.get("normRequired")),
                    Map.entry("unitsPerProduct", (root, cb,jm)->root.get("unitsPerProduct")),
                    Map.entry("normDate", (root, cb,jm)->root.get("normDate")),
                    Map.entry("productId", (root, cb,jm)->productJoin(jm, cb).get("id")),
                    Map.entry("productName", (root, cb,jm)->productJoin(jm, cb).get("productName")),
                    Map.entry("catalogNumber", (root, cb,jm)->productJoin(jm, cb).get("catalogNumber")),
                    /*
                     * Filtered through the RELATION, not the joined id: the join is
                     * LEFT, so IS_NULL on the joined id would also be true for a row
                     * whose join simply found nothing. The reference itself says
                     * whether the operation carries a category.
                     */
                    Map.entry("workCodeCategoryId", (root, cb,jm)->root.get("workCodeCategory").get("id"))
            );

    private static Join<Operation, Product> productJoin(
            JoinManager<Operation> joinManager,
            CriteriaBuilder cb
    ){
        Join<Operation, Product> products = joinManager.join("product", JoinType.LEFT);
        products.on(cb.isNull(products.get("archivedAt")));
        return products;
    }

    @Override
    public Path<?> resolvePath(String fieldName, Root<Operation> root, CriteriaBuilder cb, JoinManager<Operation> joinManager){
        TriFunction<Root<Operation>, CriteriaBuilder, JoinManager<Operation>, Path<?>> resolver = FIELD_MAP.get(fieldName);
        if(resolver == null){
            throw new IllegalArgumentException("Invalid filter field: " + fieldName);
        }
        return resolver.apply(root, cb, joinManager);
    }

    @Override
    public List<String> getGlobalSearchFields(){
        return List.of("productName", "operationName", "catalogNumber");
    }
}

