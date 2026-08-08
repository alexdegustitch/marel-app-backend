package com.aleksandarparipovic.marel_app.employee.specification;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

public class EmployeeJoinContext {
    private Join<Employee, Department> department;
    private Join<Employee, EmployeeBonus> activeBonus;
    private Join<EmployeeBonus, BonusCategory> bonusCategory;
    private Join<Employee, EmployeeCompensationSchemeHistory> activeScheme;
    private Join<EmployeeCompensationSchemeHistory, CompensationScheme> scheme;

    public Join<Employee, Department> department(Root<Employee> root) {
        if (department == null) {
            // Left join on department (by department_id foreign key)
            department = root.join("department", JoinType.LEFT);
        }
        return department;
    }

    public Join<Employee, EmployeeBonus> activeBonus(Root<Employee> root, CriteriaBuilder cb) {
        if (activeBonus == null) {
            // Left join on employeeBonuses, with ON clause to filter only active (endDate is null)
            activeBonus = root.join("employeeBonuses", JoinType.LEFT);
            activeBonus.on(cb.isNull(activeBonus.get("endDate")));
        }
        return activeBonus;
    }

    public Join<EmployeeBonus, BonusCategory> bonusCategory(Root<Employee> root, CriteriaBuilder cb) {
        if (bonusCategory == null) {
            // Join bonusCategory through the activeBonus join
            bonusCategory = activeBonus(root, cb).join("bonusCategory", JoinType.LEFT);
        }
        return bonusCategory;
    }

    /**
     * The scheme period in force today — the open, unarchived one.
     *
     * <p>Same device as {@link #activeBonus}: a LEFT join narrowed by an ON
     * clause, so an employee with no open period still appears in the list with
     * nulls rather than dropping out of it.
     */
    public Join<Employee, EmployeeCompensationSchemeHistory> activeScheme(Root<Employee> root, CriteriaBuilder cb) {
        if (activeScheme == null) {
            activeScheme = root.join("compensationSchemePeriods", JoinType.LEFT);
            activeScheme.on(cb.and(
                    cb.isNull(activeScheme.get("validUntil")),
                    cb.isNull(activeScheme.get("archivedAt"))
            ));
        }
        return activeScheme;
    }

    public Join<EmployeeCompensationSchemeHistory, CompensationScheme> scheme(Root<Employee> root, CriteriaBuilder cb) {
        if (scheme == null) {
            scheme = activeScheme(root, cb).join("compensationScheme", JoinType.LEFT);
        }
        return scheme;
    }
}
