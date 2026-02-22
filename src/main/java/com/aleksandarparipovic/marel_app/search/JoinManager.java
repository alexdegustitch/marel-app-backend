package com.aleksandarparipovic.marel_app.search;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

import java.util.HashMap;
import java.util.Map;

public class JoinManager<T> {
    private final Root<T> root;
    // Cache of first-level joins by attribute name
    private final Map<String, Join<T, ?>> joins = new HashMap<>();
    // Cache of nested joins, key as "parentAttr.nestedAttr"
    private final Map<String, Join<?, ?>> nestedJoins = new HashMap<>();

    public JoinManager(Root<T> root) {
        this.root = root;
    }

    /** Join a direct relation from the root entity. */
    @SuppressWarnings("unchecked")
    public <J> Join<T, J> join(String attribute, JoinType type) {
        if (joins.containsKey(attribute)) {
            return (Join<T, J>) joins.get(attribute);
        }
        Join<T, J> join = root.join(attribute, type);
        joins.put(attribute, join);
        return join;
    }

    /** Join a nested relation from an existing join (e.g., join from a joined entity). */
    @SuppressWarnings("unchecked")
    public <P, J> Join<P, J> join(Join<?, P> parentJoin, String attribute, JoinType type) {
        String key = parentJoin.getAttribute().getName() + "." + attribute;
        if (nestedJoins.containsKey(key)) {
            return (Join<P, J>) nestedJoins.get(key);
        }
        Join<P, J> join = parentJoin.join(attribute, type);
        nestedJoins.put(key, join);
        return join;
    }
}
