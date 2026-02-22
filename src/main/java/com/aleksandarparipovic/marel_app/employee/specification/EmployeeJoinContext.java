package com.aleksandarparipovic.marel_app.employee.specification;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

public class EmployeeJoinContext {
    private Join<Employee, Department> department;
    private Join<Employee, EmployeeBonus> activeBonus;
    private Join<EmployeeBonus, BonusCategory> bonusCategory;

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
}
