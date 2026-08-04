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

    /**
     * One item's category rows, in the order the categories are configured to
     * appear in.
     *
     * <p>Ordered here rather than in the caller because this is what the payroll
     * screen and the PDF both render directly — neither re-sorts. Without the
     * ORDER BY the sequence is whatever PostgreSQL happens to return, which is
     * stable enough to look deliberate and then changes after an update.
     *
     * <p>{@code id} breaks ties so the order is total even if two categories are
     * ever given the same display_order.
     */
    @Query("""
            SELECT c FROM PayrollRunItemCategory c
            JOIN FETCH c.workCodeCategory wcc
            WHERE c.payrollRunItem.id = :itemId
            ORDER BY wcc.displayOrder ASC, wcc.id ASC
            """)
    List<PayrollRunItemCategory> findByPayrollRunItemIdWithWorkCodeCategory(@Param("itemId") Long itemId);

    void deleteAllByPayrollRunItemId(Long payrollRunItemId);

    /**
     * @deprecated Retroactive repricing — do not use. Writing a rate onto items
     * this way overwrites months the rate was never in force for, which is the
     * defect employee_payroll_value_history exists to close. Record the rate with
     * {@code EmployeePayrollValueService.setValue} and call
     * {@link #markNeedsRecalculationByEmployeeId} instead: each item then
     * re-resolves the rate for ITS OWN month. Kept only so an existing caller
     * outside this repository is not silently removed.
     */
    @Deprecated
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
