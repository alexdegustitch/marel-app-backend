package com.aleksandarparipovic.marel_app.employee_bonus;

import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public interface EmployeeBonusRepository
        extends JpaRepository<EmployeeBonus, Long>, JpaSpecificationExecutor<EmployeeBonus> {

    Optional<EmployeeBonus> findByEmployeeIdAndEndDateIsNull(Long employeeId);

    @Query("""
    SELECT eb FROM EmployeeBonus eb
    WHERE eb.employee.id = :employeeId
    AND eb.bonusCategory.id = :categoryId
    AND eb.endDate is null
""")
    Optional<EmployeeBonus> findActiveBonus(
            Long employeeId,
            Long categoryId
    );

}
