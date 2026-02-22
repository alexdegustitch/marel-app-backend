package com.aleksandarparipovic.marel_app.product.repository;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationCountRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class ProductRepositoryImpl implements ProductRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public <T> Page<T> searchWithProjection(
            Specification<Product> specification,
            Pageable pageable,
            Class<T> projectionType
    ) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<T> query = cb.createQuery(projectionType);
        Root<Product> root = query.from(Product.class);
        Join<Product, Operation> operationJoin = root.join("operations", JoinType.LEFT);
        operationJoin.on(cb.isNull(operationJoin.get("archivedAt")));

        if (specification != null) {
            Predicate predicate = specification.toPredicate(root, query, cb);
            if (predicate != null) {
                query.where(predicate);
            }
        }

        Expression<Long> operationCountExpr = cb.countDistinct(operationJoin.get("id"));

        if (projectionType.equals(ProductWithOperationCountRow.class)) {
            query.groupBy(
                    root.get("id"),
                    root.get("productName"),
                    root.get("productCode"),
                    root.get("description"),
                    root.get("active")
            );
            query.select(cb.construct(
                    projectionType,
                    root.get("id"),
                    root.get("productName"),
                    root.get("productCode"),
                    root.get("description"),
                    root.get("active"),
                    operationCountExpr
            ));
        } else {
            throw new UnsupportedOperationException("Unsupported projection type: " + projectionType.getName());
        }

        applySorting(query, cb, root, pageable.getSort(), operationCountExpr);

        TypedQuery<T> typedQuery = em.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<T> content = typedQuery.getResultList();

        long total = count(specification, cb);
        return new PageImpl<>(content, pageable, total);
    }

    private void applySorting(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            Root<Product> root,
            Sort sort,
            Expression<Long> operationCountExpr
    ) {
        if (sort == null || sort.isUnsorted()) {
            query.orderBy(cb.asc(root.get("id")));
            return;
        }

        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Expression<?> sortExpression = resolveSortPath(root, order.getProperty(), operationCountExpr);
            orders.add(order.isAscending() ? cb.asc(sortExpression) : cb.desc(sortExpression));
        }
        query.orderBy(orders);
    }

    private Expression<?> resolveSortPath(
            Root<Product> root,
            String property,
            Expression<Long> operationCountExpr
    ) {
        return switch (property) {
            case "id", "productId" -> root.get("id");
            case "productName" -> root.get("productName");
            case "productCode" -> root.get("productCode");
            case "description" -> root.get("description");
            case "active" -> root.get("active");
            case "createdAt" -> root.get("createdAt");
            case "updatedAt" -> root.get("updatedAt");
            case "operationCount" -> operationCountExpr;
            default -> root.get(property);
        };
    }

    private long count(Specification<Product> specification, CriteriaBuilder cb) {
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Product> countRoot = countQuery.from(Product.class);

        if (specification != null) {
            Predicate predicate = specification.toPredicate(countRoot, countQuery, cb);
            if (predicate != null) {
                countQuery.where(predicate);
            }
        }

        countQuery.select(cb.countDistinct(countRoot));
        return em.createQuery(countQuery).getSingleResult();
    }
}
