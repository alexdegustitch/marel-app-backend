package com.aleksandarparipovic.marel_app.product_type;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

public class ProductTypeSpecifications {

    private ProductTypeSpecifications() {
    }

    /** One box over the three things a type is known by: name, code and description. */
    public static Specification<ProductType> matches(String value) {
        String needle = "%" + value.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate byName = cb.like(cb.lower(root.get("name")), needle);
            Predicate byCode = cb.like(cb.lower(root.get("code")), needle);
            Predicate byDescription = cb.like(cb.lower(root.get("description")), needle);
            return cb.or(byName, byCode, byDescription);
        };
    }

    public static Specification<ProductType> inFamily(Long familyId) {
        return (root, query, cb) -> cb.equal(root.get("family").get("id"), familyId);
    }

    public static Specification<ProductType> isActive(Boolean active) {
        return (root, query, cb) -> cb.equal(root.get("isActive"), active);
    }
}
