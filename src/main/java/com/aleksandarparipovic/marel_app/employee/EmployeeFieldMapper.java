package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.search.EntityFieldMapper;
import com.aleksandarparipovic.marel_app.search.JoinManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Map;

public final class EmployeeFieldMapper implements EntityFieldMapper<Employee> {

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private static final Map<String, TriFunction<Root<Employee>, CriteriaBuilder, JoinManager<Employee>, Path<?>>> FIELD_MAP =
            Map.ofEntries(
                    Map.entry("id", (root, cb, jm) -> root.get("id")),
                    Map.entry("employeeId", (root, cb, jm) -> root.get("id")),
                    Map.entry("employeeNo", (root, cb, jm) -> root.get("employeeNo")),
                    Map.entry("firstName", (root, cb, jm) -> root.get("firstName")),
                    Map.entry("lastName", (root, cb, jm) -> root.get("lastName")),
                    Map.entry("fullName", (root, cb, jm) -> root.get("fullName")),
                    Map.entry("notes", (root, cb, jm) -> root.get("notes")),
                    // The scheme code, not a boolean flag: since 2026-09-19-03 there is
                    // no is_foreigner column, and which code counts as which worker type
                    // is a presentation decision the client makes (business rules §10).
                    Map.entry("schemeCode", (root, cb, jm) -> activeSchemeJoin(jm, cb).get("code")),
                    Map.entry("active", (root, cb, jm) -> root.get("active")),
                    Map.entry("employmentStartDate", (root, cb, jm) -> root.get("employmentStartDate")),
                    Map.entry("employmentEndDate", (root, cb, jm) -> root.get("employmentEndDate")),
                    Map.entry("probationEndDate", (root, cb, jm) -> root.get("probationEndDate")),
                    Map.entry("transportAllowanceRsd", (root, cb, jm) -> root.get("transportAllowanceRsd")),
                    Map.entry("createdAt", (root, cb, jm) -> root.get("createdAt")),
                    Map.entry("updatedAt", (root, cb, jm) -> root.get("updatedAt")),
                    Map.entry("archivedAt", (root, cb, jm) -> root.get("archivedAt")),
                    Map.entry("departmentId", (root, cb, jm) -> jm.join("department", JoinType.LEFT).get("id")),
                    Map.entry("departmentName", (root, cb, jm) -> jm.join("department", JoinType.LEFT).get("name")),
                    Map.entry("bonusStart", (root, cb, jm) -> activeBonusJoin(jm, cb).get("startDate")),
                    Map.entry("categoryId", (root, cb, jm) -> bonusCategoryJoin(jm, cb).get("id")),
                    Map.entry("categoryNo", (root, cb, jm) -> bonusCategoryJoin(jm, cb).get("categoryNo")),
                    Map.entry("categoryName", (root, cb, jm) -> bonusCategoryJoin(jm, cb).get("categoryName")),
                    Map.entry("bonusAmount", (root, cb, jm) -> bonusCategoryJoin(jm, cb).get("bonusAmount"))
            );

    private static Join<EmployeeCompensationSchemeHistory, CompensationScheme> activeSchemeJoin(
            JoinManager<Employee> joinManager,
            CriteriaBuilder cb
    ) {
        Join<Employee, EmployeeCompensationSchemeHistory> openPeriod =
                joinManager.join("compensationSchemePeriods", JoinType.LEFT);
        openPeriod.on(cb.and(cb.isNull(openPeriod.get("validUntil")), cb.isNull(openPeriod.get("archivedAt"))));
        return joinManager.join(openPeriod, "compensationScheme", JoinType.LEFT);
    }

    private static Join<Employee, EmployeeBonus> activeBonusJoin(
            JoinManager<Employee> joinManager,
            CriteriaBuilder cb
    ) {
        Join<Employee, EmployeeBonus> activeBonus = joinManager.join("employeeBonuses", JoinType.LEFT);
        activeBonus.on(cb.isNull(activeBonus.get("endDate")));
        return activeBonus;
    }

    private static Join<EmployeeBonus, BonusCategory> bonusCategoryJoin(
            JoinManager<Employee> joinManager,
            CriteriaBuilder cb
    ) {
        Join<Employee, EmployeeBonus> activeBonus = activeBonusJoin(joinManager, cb);
        return joinManager.join(activeBonus, "bonusCategory", JoinType.LEFT);
    }

    @Override
    public Path<?> resolvePath(String fieldName, Root<Employee> root, CriteriaBuilder cb, JoinManager<Employee> jm) {
        TriFunction<Root<Employee>, CriteriaBuilder, JoinManager<Employee>, Path<?>> resolver = FIELD_MAP.get(fieldName);
        if (resolver == null) {
            throw new IllegalArgumentException("Invalid filter field: " + fieldName);
        }
        return resolver.apply(root, cb, jm);
    }

    @Override
    public List<String> getGlobalSearchFields() {
        // fullName rather than the two parts: it IS first || ' ' || last, so a
        // contains-match on it already finds either one, and searching all three
        // would just OR the same rows in three times.
        return List.of("employeeNo", "fullName", "departmentName", "categoryName", "notes");
    }
}
