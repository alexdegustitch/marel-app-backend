package com.aleksandarparipovic.marel_app.search;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;

public interface EntityFieldMapper<T> {
    /** Resolve the JPA Path for a given field name, using joins if needed. */
    Path<?> resolvePath(String fieldName, Root<T> root, CriteriaBuilder cb, JoinManager<T> joinManager);

    /** List of field names to apply global search on (for building OR predicates). */
    default List<String> getGlobalSearchFields() {
        return List.of(); // default no global search fields
    }
}
