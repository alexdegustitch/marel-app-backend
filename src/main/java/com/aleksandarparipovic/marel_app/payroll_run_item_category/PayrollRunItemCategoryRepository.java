package com.aleksandarparipovic.marel_app.payroll_run_item_category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Repository
public interface PayrollRunItemCategoryRepository extends JpaRepository<PayrollRunItemCategory, Long>, JpaSpecificationExecutor<PayrollRunItemCategory> {

    List<PayrollRunItemCategory> findByPayrollRunItem_IdIn(Collection<Long> itemIds);

    @Query("SELECT c FROM PayrollRunItemCategory c JOIN FETCH c.workCodeCategory WHERE c.payrollRunItem.id = :itemId")
    List<PayrollRunItemCategory> findByPayrollRunItemIdWithWorkCodeCategory(@Param("itemId") Long itemId);

    void deleteAllByPayrollRunItemId(Long payrollRunItemId);

    @Modifying
    @Query("""
        UPDATE PayrollRunItemCategory c
        SET c.hourlyRate = :newRate
        WHERE c.payrollRunItem.id IN (
            SELECT pri.id FROM PayrollRunItem pri
            WHERE pri.employee.id = :employeeId
              AND pri.status != 'LOCKED'
              AND pri.archivedAt IS NULL
              AND (pri.hourlyRateOverridden IS NULL OR pri.hourlyRateOverridden = false)
        )
        AND (c.workCodeCategory.fixedHourlyRate IS NULL OR c.workCodeCategory.fixedHourlyRate = false)
        """)
    int updateHourlyRateByEmployeeId(@Param("employeeId") Long employeeId,
                                     @Param("newRate") BigDecimal newRate);
}
