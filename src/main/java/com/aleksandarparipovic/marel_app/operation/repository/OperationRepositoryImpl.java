package com.aleksandarparipovic.marel_app.operation.repository;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWithProductInfoRow;
import com.aleksandarparipovic.marel_app.operation.specification.OperationSpecifications;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.search.JoinManager;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
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
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class OperationRepositoryImpl implements OperationRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public <T> Page<T> searchWithProjection(
            Specification<Operation> specification,
            Pageable pageable,
            Class<T> projectionType
    ) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(projectionType);
        Root<Operation> root = query.from(Operation.class);
        JoinManager<Operation> joinManager = new JoinManager<>(root);

        if (specification != null) {
            Predicate predicate = specification.toPredicate(root, query, cb);
            if (predicate != null) {
                query.where(predicate);
            }
        }

        Expression<Long> productCountExpression = productCountExpression(query, cb, root);

        if (projectionType.equals(OperationWithProductInfoRow.class)) {
            query.select(cb.construct(
                    projectionType,
                    root.get("id"),
                    productJoin(joinManager, cb).get("id"),
                    root.get("opName"),
                    productJoin(joinManager, cb).get("productName"),
                    root.get("minNorm"),
                    root.get("maxNorm"),
                    root.get("unitsPerProduct"),
                    root.get("normDate"),
                    productCountExpression
            ));
        } else {
            throw new UnsupportedOperationException("Unsupported projection type " + projectionType.getName());
        }

        applySorting(query, cb, joinManager, root, pageable.getSort(), productCountExpression);

        TypedQuery<T> typedQuery = em.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<T> content = typedQuery.getResultList();

        long total = count(specification, cb);
        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public Page<OperationWithProductInfoRow> searchNative(SearchRequest request, Pageable pageable) {
        Specification<Operation> specification = OperationSpecifications.fromSearchRequest(request);
        return searchWithProjection(specification, pageable, OperationWithProductInfoRow.class);
    }

    private void applySorting(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            JoinManager<Operation> joinManager,
            Root<Operation> root,
            Sort sort,
            Expression<Long> productCountExpression
    ) {
        if (sort == null || sort.isUnsorted()) {
            query.orderBy(cb.asc(productJoin(joinManager, cb).get("id")), cb.asc(root.get("id")));
            return;
        }

        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Expression<?> sortExpression = resolveSortPath(root, cb, joinManager, order.getProperty(), productCountExpression);
            orders.add(order.isAscending() ? cb.asc(sortExpression) : cb.desc(sortExpression));
        }
        query.orderBy(orders);
    }

    private Expression<?> resolveSortPath(
            Root<Operation> root,
            CriteriaBuilder cb,
            JoinManager<Operation> joinManager,
            String property,
            Expression<Long> productCountExpression
    ) {
        return switch (property) {
            case "id", "operationId" -> root.get("id");
            case "operationName" -> root.get("opName");
            case "minNorm" -> root.get("minNorm");
            case "maxNorm" -> root.get("maxNorm");
            case "unitsPerProduct" -> root.get("unitsPerProduct");
            case "normDate" -> root.get("normDate");
            case "productName" -> productJoin(joinManager, cb).get("productName");
            case "productId" -> productJoin(joinManager, cb).get("id");
            case "productCount" -> productCountExpression;
            default -> root.get(property);
        };
    }

    private long count(Specification<Operation> specification, CriteriaBuilder cb) {
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Operation> countRoot = countQuery.from(Operation.class);

        if (specification != null) {
            Predicate predicate = specification.toPredicate(countRoot, countQuery, cb);
            if (predicate != null) {
                countQuery.where(predicate);
            }
        }

        countQuery.select(cb.countDistinct(countRoot));
        return em.createQuery(countQuery).getSingleResult();
    }

    private static Join<Operation, Product> productJoin(
            JoinManager<Operation> joinManager,
            CriteriaBuilder cb
    ) {
        Join<Operation, Product> products = joinManager.join("product", JoinType.LEFT);
        products.on(cb.isNull(products.get("archivedAt")));
        return products;
    }

    private Expression<Long> productCountExpression(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            Root<Operation> root
    ) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<Operation> subRoot = subquery.from(Operation.class);
        subquery.select(cb.count(subRoot));
        subquery.where(
                cb.equal(subRoot.get("product"), root.get("product")),
                cb.isNull(subRoot.get("archivedAt"))
        );
        return subquery;
    }
}
