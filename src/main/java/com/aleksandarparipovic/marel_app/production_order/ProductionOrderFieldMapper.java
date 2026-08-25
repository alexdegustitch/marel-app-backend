package com.aleksandarparipovic.marel_app.production_order;

import com.aleksandarparipovic.marel_app.search.EntityFieldMapper;
import com.aleksandarparipovic.marel_app.search.JoinManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Map;

public final class ProductionOrderFieldMapper implements EntityFieldMapper<ProductionOrder> {

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private static final Map<String, TriFunction<Root<ProductionOrder>, CriteriaBuilder, JoinManager<ProductionOrder>, Path<?>>> FIELD_MAP =
            Map.ofEntries(
                    Map.entry("id", (root, cb, jm) -> root.get("id")),
                    Map.entry("code", (root, cb, jm) -> root.get("code")),
                    Map.entry("name", (root, cb, jm) -> root.get("name")),
                    Map.entry("note", (root, cb, jm) -> root.get("note")),
                    Map.entry("status", (root, cb, jm) -> root.get("status")),
                    Map.entry("testingRequired", (root, cb, jm) -> root.get("testingRequired")),
                    Map.entry("creationDate", (root, cb, jm) -> root.get("creationDate")),
                    Map.entry("orderDate", (root, cb, jm) -> root.get("orderDate")),
                    Map.entry("deliveryDeadline", (root, cb, jm) -> root.get("deliveryDeadline")),
                    Map.entry("isHighPriority", (root, cb, jm) -> root.get("isHighPriority")),
                    Map.entry("isAnnounced", (root, cb, jm) -> root.get("isAnnounced")),
                    Map.entry("hasSuccessiveDeliveries", (root, cb, jm) -> root.get("hasSuccessiveDeliveries")),
                    Map.entry("isActive", (root, cb, jm) -> root.get("isActive")),
                    Map.entry("createdAt", (root, cb, jm) -> root.get("createdAt")),
                    /*
                     * The customer id needs no join — it IS the foreign key column
                     * on this table, and reaching it through one would cost a join
                     * to answer a question the row already answers.
                     */
                    Map.entry("customerId", (root, cb, jm) -> root.get("customer").get("id")),
                    /*
                     * LEFT, and it matters. Most orders are for nobody outside, and
                     * an inner join would drop every one of them from the list the
                     * moment somebody sorted or filtered by customer — as a silent
                     * shortening, not an error.
                     */
                    Map.entry("customerName", (root, cb, jm) -> jm.join("customer", JoinType.LEFT).get("name")),
                    Map.entry("customerCode", (root, cb, jm) -> jm.join("customer", JoinType.LEFT).get("code"))
            );

    @Override
    public Path<?> resolvePath(String fieldName, Root<ProductionOrder> root, CriteriaBuilder cb, JoinManager<ProductionOrder> joinManager) {
        TriFunction<Root<ProductionOrder>, CriteriaBuilder, JoinManager<ProductionOrder>, Path<?>> resolver = FIELD_MAP.get(fieldName);
        if (resolver == null) {
            throw new IllegalArgumentException("Invalid filter field: " + fieldName);
        }
        return resolver.apply(root, cb, joinManager);
    }

    @Override
    public List<String> getGlobalSearchFields() {
        return List.of("code", "name");
    }
}
