package com.aleksandarparipovic.marel_app.customer;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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

    /**
     * Customers with at least one live production order still in CREATED — the
     * ones work is currently running for. EXISTS rather than a join, so a
     * customer with five open orders is one row, not five.
     */
    public static Specification<Customer> hasActiveProductionOrders() {
        return (root, query, cb) -> {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<ProductionOrder> order = sub.from(ProductionOrder.class);
            sub.select(cb.literal(1)).where(
                    cb.equal(order.get("customer"), root),
                    cb.isNull(order.get("archivedAt")),
                    cb.equal(order.get("status"), ProductionOrderStatus.CREATED));
            return cb.exists(sub);
        };
    }
}
