package com.aleksandarparipovic.marel_app.employee.repository;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDirectorySummary;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import com.aleksandarparipovic.marel_app.employee.specification.EmployeeJoinContext;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import jakarta.persistence.criteria.Nulls;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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
        Join<Employee, WorkCodeCategory> workCategoryJoin = root.join("defaultWorkCategory", JoinType.LEFT);
        Join<EmployeeCompensationSchemeHistory, CompensationScheme> schemeJoin = joins.scheme(root, cb);

        // Select distinct employees with their bonus info (construct DTO)
        query.distinct(true);
        query.select(cb.construct(
                EmployeeWithBonusView.class,
                root.get("id"),
                root.get("employeeNo"),
                root.get("firstName"),
                root.get("lastName"),
                root.get("fullName"),
                departmentJoin.get("name"),
                departmentJoin.get("id"),
                root.get("employmentStartDate"),
                root.get("probationEndDate"),
                root.get("notes"),
                root.get("transportAllowanceRsd"),
                root.get("transportAllowanceMode"),
                bonusCategoryJoin.get("categoryNo"),
                bonusCategoryJoin.get("id"),
                bonusCategoryJoin.get("categoryName"),
                bonusCategoryJoin.get("bonusAmount"),
                activeBonusJoin.get("startDate"),
                schemeJoin.get("code"),
                schemeJoin.get("name"),
                schemeJoin.get("allowsPerformanceBonus"),
                root.get("mobilePhone"),
                root.get("email"),
                root.get("hourlyRate"),
                workCategoryJoin.get("id"),
                workCategoryJoin.get("categoryName"),
                root.get("preferredLocale")
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
                    case "departmentName" -> sortPath = departmentJoin.get("name");
                    case "departmentId" -> sortPath = departmentJoin.get("id");
                    case "categoryNo" -> sortPath = bonusCategoryJoin.get("categoryNo");
                    case "categoryName" -> sortPath = bonusCategoryJoin.get("categoryName");
                    case "bonusAmount" -> sortPath = bonusCategoryJoin.get("bonusAmount");
                    case "bonusStart" -> sortPath = activeBonusJoin.get("startDate");
                    case "defaultWorkCategoryName" -> sortPath = workCategoryJoin.get("categoryName");
                    default -> sortPath = root.get(field);
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
        Join<Employee, WorkCodeCategory> workCategoryJoin = root.join("defaultWorkCategory", JoinType.LEFT);
        Join<EmployeeCompensationSchemeHistory, CompensationScheme> schemeJoin = joins.scheme(root, cb);

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
                    root.get("firstName"),
                    root.get("lastName"),
                    root.get("fullName"),
                    departmentJoin.get("name"),
                    departmentJoin.get("id"),
                    root.get("employmentStartDate"),
                    root.get("probationEndDate"),
                    root.get("notes"),
                    root.get("transportAllowanceRsd"),
                    root.get("transportAllowanceMode"),
                    bonusCategoryJoin.get("categoryNo"),
                    bonusCategoryJoin.get("id"),
                    bonusCategoryJoin.get("categoryName"),
                    bonusCategoryJoin.get("bonusAmount"),
                    activeBonusJoin.get("startDate"),
                    schemeJoin.get("code"),
                    schemeJoin.get("name"),
                    schemeJoin.get("allowsPerformanceBonus"),
                    root.get("mobilePhone"),
                    root.get("email"),
                    root.get("hourlyRate"),
                    workCategoryJoin.get("id"),
                    workCategoryJoin.get("categoryName"),
                    root.get("preferredLocale")
            ));
        } else {
            throw new UnsupportedOperationException("Unsupported projection type: " + projectionType.getName());
        }

        // Sorting.
        //
        // ALWAYS ends on the id, whatever the caller asked for. A page is a
        // window over an ORDER BY, and two employees who tie on the sort column
        // — the same surname, the same department, no bonus category at all —
        // have no defined order between them; Postgres is free to return them
        // one way for page 1 and the other way for page 2, so one of them
        // appears twice and the other never. With no sort at all it was worse:
        // there was no ORDER BY, and the DISTINCT made the row order whatever
        // the hash happened to produce. The id settles every tie the same way
        // on every page, and is the primary key, so an unsorted first page is
        // an index walk rather than a sort of the whole table.
        List<Order> orders = new ArrayList<>();
        if (pageable.getSort().isSorted()) {

            for (Sort.Order order : pageable.getSort()) {
                Expression<?> sortExpr;

                switch (order.getProperty()) {
                    case "departmentName" -> sortExpr = departmentJoin.get("name");
                    case "departmentId" -> sortExpr = departmentJoin.get("id");
                    case "categoryNo" -> sortExpr = bonusCategoryJoin.get("categoryNo");
                    case "categoryName" -> sortExpr = bonusCategoryJoin.get("categoryName");
                    case "bonusAmount" -> sortExpr = bonusCategoryJoin.get("bonusAmount");
                    case "bonusStart" -> sortExpr = activeBonusJoin.get("startDate");
                    case "transportAllowanceRsd" -> {
                        // Everyone on a FIXED allowance first, ordered by amount;
                        // the AUTO ones after them, since they have no amount to
                        // compare. 'FIXED' > 'AUTO' as text, so DESC on the mode
                        // gives that grouping in BOTH directions — the people
                        // without a fixed figure stay at the bottom either way.
                        orders.add(cb.desc(root.get("transportAllowanceMode")));
                        Path<Number> amount = root.get("transportAllowanceRsd");
                        orders.add(order.isAscending()
                                ? cb.asc(amount, Nulls.LAST)
                                : cb.desc(amount, Nulls.LAST));
                        continue;
                    }
                    case "defaultWorkCategoryName" -> sortExpr = workCategoryJoin.get("categoryName");
                    default -> sortExpr = root.get(order.getProperty());
                }

                // NULLS LAST in BOTH directions: an employee with no bonus
                // category belongs at the bottom whichever way the column is
                // sorted, not floated to the top by a descending sort.
                //
                // The JPA 3.2 Nulls overload, NOT coalesce(): this query is
                // SELECT DISTINCT, and Postgres refuses an ORDER BY expression
                // that is not in the select list — which is exactly what the
                // earlier coalesce() did.
                orders.add(order.isAscending()
                        ? cb.asc(sortExpr, Nulls.LAST)
                        : cb.desc(sortExpr, Nulls.LAST));
            }
        }
        orders.add(cb.asc(root.get("id")));
        query.orderBy(orders);


        // Execute main query
        TypedQuery<T> typedQuery = em.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<T> content = typedQuery.getResultList();

        // The count is the expensive half of a paged read over a large table —
        // the page itself stops after fifty rows, the COUNT(DISTINCT) does not.
        // It is only run when the page cannot tell the total on its own: a
        // first page that came back short IS the whole result, and a later
        // page that came back short ends it. Same rule Spring Data applies to
        // its own derived queries.
        return PageableExecutionUtils.getPage(content, pageable, () -> countMatching(spec, cb));
    }

    private long countMatching(Specification<Employee> spec, CriteriaBuilder cb) {
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Employee> countRoot = countQuery.from(Employee.class);
        if (spec != null) {
            Predicate countPredicate = spec.toPredicate(countRoot, countQuery, cb);
            if (countPredicate != null) countQuery.where(countPredicate);
        }
        countQuery.select(cb.countDistinct(countRoot));
        return em.createQuery(countQuery).getSingleResult();
    }

    /**
     * The three headline figures in two statements, however many employees
     * match.
     *
     * <p>The per-scheme counts come from ONE grouped query over the same
     * predicate the page uses, with the same "open, unarchived period" join the
     * projection uses for the scheme column — so the tiles above the table and
     * the badge beside each name can never disagree about who is on what. The
     * total is the sum of the groups (an employee has at most one open period,
     * and the group with a null code holds the ones with none), which saves the
     * third statement that a separate COUNT would have been.
     */
    @Override
    public EmployeeDirectorySummary directorySummary(Specification<Employee> spec, LocalDate today) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<EmployeeDirectorySummary.SchemeCount> bySchemeQuery =
                cb.createQuery(EmployeeDirectorySummary.SchemeCount.class);
        Root<Employee> root = bySchemeQuery.from(Employee.class);
        EmployeeJoinContext joins = new EmployeeJoinContext();
        Join<EmployeeCompensationSchemeHistory, CompensationScheme> schemeJoin = joins.scheme(root, cb);

        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, bySchemeQuery, cb);
            if (predicate != null) bySchemeQuery.where(predicate);
        }
        // The specification switches DISTINCT on for the page it was written
        // for; on a grouped query it is meaningless and only costs a sort.
        bySchemeQuery.distinct(false);
        bySchemeQuery.select(cb.construct(
                EmployeeDirectorySummary.SchemeCount.class,
                schemeJoin.get("code"),
                schemeJoin.get("name"),
                cb.countDistinct(root.get("id"))
        ));
        bySchemeQuery.groupBy(schemeJoin.get("code"), schemeJoin.get("name"));
        bySchemeQuery.orderBy(cb.asc(schemeJoin.get("code")));
        List<EmployeeDirectorySummary.SchemeCount> byScheme = em.createQuery(bySchemeQuery).getResultList();

        long total = byScheme.stream().mapToLong(EmployeeDirectorySummary.SchemeCount::count).sum();

        CriteriaQuery<Long> probationQuery = cb.createQuery(Long.class);
        Root<Employee> probationRoot = probationQuery.from(Employee.class);
        List<Predicate> probationPredicates = new ArrayList<>();
        if (spec != null) {
            Predicate predicate = spec.toPredicate(probationRoot, probationQuery, cb);
            if (predicate != null) probationPredicates.add(predicate);
        }
        // "Still on probation" is what the table's own column shows: a
        // probation end date that has not passed yet.
        probationPredicates.add(cb.greaterThanOrEqualTo(probationRoot.get("probationEndDate"), today));
        probationQuery.distinct(false);
        probationQuery.select(cb.countDistinct(probationRoot.get("id")))
                .where(probationPredicates.toArray(Predicate[]::new));
        long onProbation = em.createQuery(probationQuery).getSingleResult();

        return new EmployeeDirectorySummary(total, onProbation, byScheme);
    }
}
