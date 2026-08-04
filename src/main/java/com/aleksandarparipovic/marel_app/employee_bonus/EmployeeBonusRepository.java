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

    /**
     * The bonus category this employee belonged to ON {@code date}.
     *
     * <p>By date, not "the currently open one": recalculating an old month has to
     * use the category the employee was in THEN. Moving somebody to a different
     * bonus category must not retroactively change what earlier months paid.
     *
     * <p>{@code end_date} is inclusive, matching the other date-effective
     * histories in this schema.
     */
    @Query("""
    SELECT eb FROM EmployeeBonus eb
    JOIN FETCH eb.bonusCategory bc
    WHERE eb.employee.id = :employeeId
      AND eb.startDate <= :date
      AND (eb.endDate IS NULL OR eb.endDate >= :date)
""")
    Optional<EmployeeBonus> findInForce(
            @org.springframework.data.repository.query.Param("employeeId") Long employeeId,
            @org.springframework.data.repository.query.Param("date") java.time.LocalDate date
    );

}
