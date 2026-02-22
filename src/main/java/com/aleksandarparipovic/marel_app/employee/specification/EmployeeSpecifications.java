package com.aleksandarparipovic.marel_app.employee.specification;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.EmployeeFieldMapper;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.search.SearchSpecification;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

public final class EmployeeSpecifications {

    private static final EmployeeFieldMapper FIELD_MAPPER = new EmployeeFieldMapper();

    private EmployeeSpecifications() {
    }

    public static Specification<Employee> notArchived() {
        return (root, q, cb) -> cb.isNull(root.get("archivedAt"));
    }

    public static Specification<Employee> isActive(Boolean active) {
        return (root, q, cb) ->
                active == null ? null : cb.equal(root.get("active"), active);
    }

    public static Specification<Employee> inDepartment(Long departmentId) {
        return (root, q, cb) ->
                departmentId == null ? null : cb.equal(root.get("department").get("id"), departmentId);
    }

    public static Specification<Employee> fromSearchRequest(SearchRequest request) {
        return Specification.where(notArchived())
                .and(new SearchSpecification<>(request, FIELD_MAPPER));
    }

    public static Specification<Employee> employedOn(LocalDate date) {
        return (root, q, cb) -> {
            if (date == null) return null;
            return cb.and(
                    cb.lessThanOrEqualTo(root.get("employmentStartDate"), date),
                    cb.or(
                            cb.isNull(root.get("employmentEndDate")),
                            cb.greaterThanOrEqualTo(root.get("employmentEndDate"), date)
                    )
            );
        };
    }
}
