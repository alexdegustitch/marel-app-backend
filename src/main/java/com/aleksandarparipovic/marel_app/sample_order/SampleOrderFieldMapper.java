package com.aleksandarparipovic.marel_app.sample_order;

import com.aleksandarparipovic.marel_app.search.EntityFieldMapper;
import com.aleksandarparipovic.marel_app.search.JoinManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Map;

/**
 * Which field names the sample-order list may filter and sort by.
 *
 * <p>A whitelist, not a pass-through: an unknown name would reach the criteria
 * builder as a property and come back to the screen as a stack trace instead of
 * a list.
 */
public final class SampleOrderFieldMapper implements EntityFieldMapper<SampleOrder> {

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private static final Map<String, TriFunction<Root<SampleOrder>, CriteriaBuilder, JoinManager<SampleOrder>, Path<?>>> FIELD_MAP =
            Map.ofEntries(
                    Map.entry("id", (root, cb, jm) -> root.get("id")),
                    Map.entry("code", (root, cb, jm) -> root.get("code")),
                    Map.entry("name", (root, cb, jm) -> root.get("name")),
                    Map.entry("note", (root, cb, jm) -> root.get("note")),
                    Map.entry("status", (root, cb, jm) -> root.get("status")),
                    Map.entry("creationDate", (root, cb, jm) -> root.get("creationDate")),
                    Map.entry("deadlineDate", (root, cb, jm) -> root.get("deadlineDate")),
                    Map.entry("deadlineNote", (root, cb, jm) -> root.get("deadlineNote")),
                    Map.entry("isActive", (root, cb, jm) -> root.get("isActive")),
                    Map.entry("createdAt", (root, cb, jm) -> root.get("createdAt")),
                    /*
                     * The customer id needs no join — it IS the foreign key column
                     * on this table, and reaching it through one would cost a join
                     * to answer a question the row already answers.
                     */
                    Map.entry("customerId", (root, cb, jm) -> root.get("customer").get("id")),
                    /*
                     * LEFT, and it matters. Samples made for an internal trial have
                     * no customer, and an inner join would drop every one of them
                     * the moment somebody sorted by customer — as a silent
                     * shortening, not an error.
                     */
                    Map.entry("customerName", (root, cb, jm) -> jm.join("customer", JoinType.LEFT).get("name")),
                    Map.entry("customerCode", (root, cb, jm) -> jm.join("customer", JoinType.LEFT).get("code"))
            );

    @Override
    public Path<?> resolvePath(String fieldName, Root<SampleOrder> root, CriteriaBuilder cb, JoinManager<SampleOrder> joinManager) {
        TriFunction<Root<SampleOrder>, CriteriaBuilder, JoinManager<SampleOrder>, Path<?>> resolver = FIELD_MAP.get(fieldName);
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
