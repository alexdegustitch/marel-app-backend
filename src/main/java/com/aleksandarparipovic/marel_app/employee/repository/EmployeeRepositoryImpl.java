package com.aleksandarparipovic.marel_app.employee.repository;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import com.aleksandarparipovic.marel_app.employee.specification.EmployeeJoinContext;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class EmployeeRepositoryImpl implements EmployeeRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Page<EmployeeWithBonusView> searchWithBonus(Specification<Employee> spec, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        // MAIN DATA QUERY
        CriteriaQuery<EmployeeWithBonusView> query = cb.createQuery(EmployeeWithBonusView.class);
        Root<Employee> root = query.from(Employee.class);

        // Apply specification filters if present
        Predicate predicate = (spec == null) ? null : spec.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        // Prepare joins for department and current bonus (reuse via context to avoid duplicate joins)
        EmployeeJoinContext joins = new EmployeeJoinContext();
        Join<Employee, Department> departmentJoin = joins.department(root);
        Join<Employee, EmployeeBonus> activeBonusJoin = joins.activeBonus(root, cb);
        Join<EmployeeBonus, BonusCategory> bonusCategoryJoin = joins.bonusCategory(root, cb);

        // Select distinct employees with their bonus info (construct DTO)
        query.distinct(true);
        query.select(cb.construct(
                EmployeeWithBonusView.class,
                root.get("id"),
                root.get("employeeNo"),
                root.get("fullName"),
                departmentJoin.get("name"),
                root.get("employmentStartDate"),
                root.get("probationEndDate"),
                root.get("notes"),
                root.get("transportAllowanceRsd"),
                bonusCategoryJoin.get("categoryNo"),
                bonusCategoryJoin.get("categoryName"),
                bonusCategoryJoin.get("bonusAmount"),
                activeBonusJoin.get("startDate")
        ));

        // Apply sorting if specified in the pageable
        pageable.getSort();
        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            for (org.springframework.data.domain.Sort.Order sortOrder : pageable.getSort()) {
                String field = sortOrder.getProperty();
                boolean ascending = sortOrder.isAscending();
                Path<?> sortPath;
                // Map sortable fields to the correct path (joining if necessary)
                switch (field) {
                    case "departmentName":
                        sortPath = departmentJoin.get("name");
                        break;
                    case "departmentId":
                        sortPath = departmentJoin.get("id");
                        break;
                    case "categoryNo":
                        sortPath = bonusCategoryJoin.get("categoryNo");
                        break;
                    case "categoryName":
                        sortPath = bonusCategoryJoin.get("categoryName");
                        break;
                    case "bonusAmount":
                        sortPath = bonusCategoryJoin.get("bonusAmount");
                        break;
                    case "bonusStart":
                        sortPath = activeBonusJoin.get("startDate");
                        break;
                    default:
                        // Default: sort by a field in the Employee root
                        sortPath = root.get(field);
                }
                orders.add(ascending ? cb.asc(sortPath) : cb.desc(sortPath));
            }
            query.orderBy(orders);
        }

        // Execute the query for paginated content
        TypedQuery<EmployeeWithBonusView> typedQuery = em.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<EmployeeWithBonusView> content = typedQuery.getResultList();

        // COUNT QUERY for total elements
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Employee> countRoot = countQuery.from(Employee.class);
        Predicate countPredicate = (spec == null) ? null : spec.toPredicate(countRoot, countQuery, cb);
        countQuery.select(cb.countDistinct(countRoot));
        if (countPredicate != null) {
            countQuery.where(countPredicate);
        }
        Long totalCount = em.createQuery(countQuery).getSingleResult();

        // Return the results as a Page
        return new PageImpl<>(content, pageable, totalCount);
    }

    @Override
    public <T> Page<T> searchWithProjection(Specification<Employee> spec, Pageable pageable, Class<T> projectionType) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(projectionType);
        Root<Employee> root = query.from(Employee.class);

        // Reuse join context
        EmployeeJoinContext joins = new EmployeeJoinContext();
        Join<Employee, Department> departmentJoin = joins.department(root);
        Join<Employee, EmployeeBonus> activeBonusJoin = joins.activeBonus(root, cb);
        Join<EmployeeBonus, BonusCategory> bonusCategoryJoin = joins.bonusCategory(root, cb);

        // Apply filters
        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, query, cb);
            if (predicate != null) query.where(predicate);
        }

        // Projection mapping
        if (projectionType.equals(EmployeeWithBonusView.class)) {
            query.select(cb.construct(
                    projectionType,
                    root.get("id"),
                    root.get("employeeNo"),
                    root.get("fullName"),
                    departmentJoin.get("name"),
                    departmentJoin.get("id"),
                    root.get("employmentStartDate"),
                    root.get("probationEndDate"),
                    root.get("notes"),
                    root.get("transportAllowanceRsd"),
                    bonusCategoryJoin.get("categoryNo"),
                    bonusCategoryJoin.get("id"),
                    bonusCategoryJoin.get("categoryName"),
                    bonusCategoryJoin.get("bonusAmount"),
                    activeBonusJoin.get("startDate"),
                    root.get("foreigner")
            ));
        } else {
            throw new UnsupportedOperationException("Unsupported projection type: " + projectionType.getName());
        }

        // Sorting
        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();

            for (Sort.Order order : pageable.getSort()) {
                Expression<?> sortExpr;

                switch (order.getProperty()) {
                    case "departmentName" -> sortExpr = departmentJoin.get("name");
                    case "departmentId" -> sortExpr = departmentJoin.get("id");
                    case "categoryNo" -> sortExpr = bonusCategoryJoin.get("categoryNo");
                    case "categoryName" -> sortExpr = bonusCategoryJoin.get("categoryName");
                    case "bonusAmount" -> {
                        Path<Number> path = bonusCategoryJoin.get("bonusAmount");
                        sortExpr = order.isAscending() ? cb.coalesce(path, Integer.MAX_VALUE) : cb.coalesce(path, Integer.MIN_VALUE);
                    }
                    case "bonusStart" -> sortExpr = activeBonusJoin.get("startDate");
                    case "transportAllowanceRsd" -> {
                        Path<Number> path = root.get("transportAllowanceRsd");
                        sortExpr = order.isAscending() ? cb.coalesce(path, Integer.MAX_VALUE) : cb.coalesce(path, Integer.MIN_VALUE);
                    }
                    default -> sortExpr = root.get(order.getProperty());
                }

                orders.add(order.isAscending() ? cb.asc(sortExpr) : cb.desc(sortExpr));
            }

            query.orderBy(orders);
        }


        // Execute main query
        TypedQuery<T> typedQuery = em.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<T> content = typedQuery.getResultList();

        // Count query
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Employee> countRoot = countQuery.from(Employee.class);
        if (spec != null) {
            Predicate countPredicate = spec.toPredicate(countRoot, countQuery, cb);
            if (countPredicate != null) countQuery.where(countPredicate);
        }
        countQuery.select(cb.countDistinct(countRoot));
        Long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }
}
