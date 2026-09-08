package com.aleksandarparipovic.marel_app.product_family;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

public class ProductFamilySpecifications {

    private ProductFamilySpecifications() {
    }

    /** One box over the two things a family is known by: its name and its description. */
    public static Specification<ProductFamily> matches(String value) {
        String needle = "%" + value.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate byName = cb.like(cb.lower(root.get("name")), needle);
            Predicate byDescription = cb.like(cb.lower(root.get("description")), needle);
            return cb.or(byName, byDescription);
        };
    }

    public static Specification<ProductFamily> isActive(Boolean active) {
        return (root, query, cb) -> cb.equal(root.get("isActive"), active);
    }
}
