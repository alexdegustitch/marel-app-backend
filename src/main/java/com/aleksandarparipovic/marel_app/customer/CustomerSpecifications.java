package com.aleksandarparipovic.marel_app.customer;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

public class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    /**
     * One box that searches the three things people know a customer by.
     *
     * <p>Name, code and tax id together, because somebody holding an invoice has
     * a tax id and somebody holding a drawing has a code, and asking them which
     * field they are about to type into is a question they should not have to
     * answer.
     */
    public static Specification<Customer> matches(String value) {
        String needle = "%" + value.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate byName = cb.like(cb.lower(root.get("name")), needle);
            Predicate byCode = cb.like(cb.lower(root.get("code")), needle);
            Predicate byTaxId = cb.like(cb.lower(root.get("taxId")), needle);
            return cb.or(byName, byCode, byTaxId);
        };
    }

    public static Specification<Customer> isActive(Boolean active) {
        return (root, query, cb) -> cb.equal(root.get("isActive"), active);
    }
}
