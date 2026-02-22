package com.aleksandarparipovic.marel_app.department;

import org.springframework.data.jpa.domain.Specification;

public class DepartmentSpecifications {

    public static Specification<Department> nameContains(String value) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("name")), "%" + value.toLowerCase() + "%");
    }

    public static Specification<Department> isActive(Boolean active) {
        return (root, query, cb) ->
                cb.equal(root.get("active"), active);
    }
}
