package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.search.EntityFieldMapper;
import com.aleksandarparipovic.marel_app.search.JoinManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Map;

public final class ProductFieldMapper implements EntityFieldMapper<Product> {

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private static final Map<String, TriFunction<Root<Product>, CriteriaBuilder, JoinManager<Product>, Path<?>>> FIELD_MAP =
            Map.ofEntries(
                    Map.entry("id", (root, cb, jm) -> root.get("id")),
                    Map.entry("productId", (root, cb, jm) -> root.get("id")),
                    Map.entry("productName", (root, cb, jm) -> root.get("productName")),
                    Map.entry("productCode", (root, cb, jm) -> root.get("productCode")),
                    Map.entry("description", (root, cb, jm) -> root.get("description")),
                    Map.entry("active", (root, cb, jm) -> root.get("active")),
                    // The table sends the DTO's name (`isActive`); the entity field is
                    // `active`. Both names resolve to the same column.
                    Map.entry("isActive", (root, cb, jm) -> root.get("active")),
                    Map.entry("createdAt", (root, cb, jm) -> root.get("createdAt")),
                    Map.entry("updatedAt", (root, cb, jm) -> root.get("updatedAt")),
                    Map.entry("archivedAt", (root, cb, jm) -> root.get("archivedAt")),
                    Map.entry("catalogNumber", (root, cb, jm) -> root.get("catalogNumber")),
                    Map.entry("subtype", (root, cb, jm) -> root.get("subtype")),
                    // Catalogue hierarchy filters. A path through productType joins in;
                    // filtering by type or family therefore returns only categorised products.
                    Map.entry("productTypeId", (root, cb, jm) -> root.get("productType").get("id")),
                    Map.entry("familyId", (root, cb, jm) -> root.get("productType").get("family").get("id")),
                    // The type's code ("CESp/CNT") — half of the product's visible "kod"
                    // (type code + subtype). A LEFT join so global search over it never
                    // drops the uncategorised products, which have no type.
                    Map.entry("typeCode", (root, cb, jm) -> typeJoin(jm).get("code")),
                    Map.entry("operationId", (root, cb, jm) -> activeOperationJoin(jm, cb).get("id")),
                    Map.entry("operationName", (root, cb, jm) -> activeOperationJoin(jm, cb).get("opName")),
                    Map.entry("operationDescription", (root, cb, jm) -> activeOperationJoin(jm, cb).get("description")),
                    Map.entry("minNorm", (root, cb, jm) -> activeOperationJoin(jm, cb).get("minNorm")),
                    Map.entry("maxNorm", (root, cb, jm) -> activeOperationJoin(jm, cb).get("maxNorm")),
                    Map.entry("normRequired", (root, cb, jm) -> activeOperationJoin(jm, cb).get("normRequired")),
                    Map.entry("unitsPerProduct", (root, cb, jm) -> activeOperationJoin(jm, cb).get("unitsPerProduct")),
                    Map.entry("normDate", (root, cb, jm) -> activeOperationJoin(jm, cb).get("normDate")),
                    Map.entry("temporary", (root, cb, jm) -> activeOperationJoin(jm, cb).get("temporary"))
            );

    private static Join<Product, Operation> activeOperationJoin(
            JoinManager<Product> joinManager,
            CriteriaBuilder cb
    ) {
        Join<Product, Operation> operations = joinManager.join("operations", JoinType.LEFT);
        operations.on(cb.isNull(operations.get("archivedAt")));
        return operations;
    }

    /**
     * A LEFT join to the product's type, so a path through it (the type code)
     * does not turn into an inner join that would drop the uncategorised products.
     */
    private static Join<Product, ?> typeJoin(JoinManager<Product> joinManager) {
        return joinManager.join("productType", JoinType.LEFT);
    }

    @Override
    public Path<?> resolvePath(String fieldName, Root<Product> root, CriteriaBuilder cb, JoinManager<Product> joinManager) {
        TriFunction<Root<Product>, CriteriaBuilder, JoinManager<Product>, Path<?>> resolver = FIELD_MAP.get(fieldName);
        if (resolver == null) {
            throw new IllegalArgumentException("Invalid filter field: " + fieldName);
        }
        return resolver.apply(root, cb, joinManager);
    }

    @Override
    public List<String> getGlobalSearchFields() {
        // The catalogue's global search: name, the product code (type code +
        // subtype), catalogue number, description and operation name. Deliberately
        // NOT productCode ("šifra") — it is a legacy field the business no longer uses.
        return List.of("productName", "typeCode", "subtype", "catalogNumber", "description", "operationName");
    }
}
