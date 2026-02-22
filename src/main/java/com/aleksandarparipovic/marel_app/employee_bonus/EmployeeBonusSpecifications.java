package com.aleksandarparipovic.marel_app.employee_bonus;

import org.springframework.data.jpa.domain.Specification;
import java.time.*;

public class EmployeeBonusSpecifications {

    public static Specification<EmployeeBonus> hasEmployee(Long employeeId) {
        return (root, q, cb) ->
                employeeId == null ? null : cb.equal(root.get("employee").get("id"), employeeId);
    }

    public static Specification<EmployeeBonus> isActive(Boolean active) {
        return (root, q, cb) -> {
            if (active == null) return null;
            return active
                    ? cb.isNull(root.get("endDate"))
                    : cb.isNotNull(root.get("endDate"));
        };
    }

    public static Specification<EmployeeBonus> startedAfter(LocalDate date) {
        return (root, q, cb) ->
                date == null ? null : cb.greaterThanOrEqualTo(root.get("startDate"), date);
    }

    public static Specification<EmployeeBonus> endedBefore(LocalDate date) {
        return (root, q, cb) ->
                date == null ? null : cb.lessThanOrEqualTo(root.get("endDate"), date);
    }
}
