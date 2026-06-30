package com.aleksandarparipovic.marel_app.payroll_adjustment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayrollAdjustmentRepository extends JpaRepository<PayrollAdjustment, Long>, JpaSpecificationExecutor<PayrollAdjustment> {

    List<PayrollAdjustment> findByPayrollRunItem_IdIn(Collection<Long> itemIds);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory WHERE a.payrollRunItem.id = :itemId")
    List<PayrollAdjustment> findByPayrollRunItemIdWithCategory(@Param("itemId") Long itemId);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory")
    List<PayrollAdjustment> findAllWithCategory();

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory WHERE a.id = :id")
    Optional<PayrollAdjustment> findByIdWithCategory(@Param("id") Long id);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory c WHERE a.payrollRunItem.id = :itemId AND c.calculationKey = :calculationKey")
    Optional<PayrollAdjustment> findByItemIdAndCalculationKey(@Param("itemId") Long itemId, @Param("calculationKey") String calculationKey);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory c WHERE a.payrollRunItem.id = :itemId AND c.code = :code")
    Optional<PayrollAdjustment> findByItemIdAndCategoryCode(@Param("itemId") Long itemId, @Param("code") String code);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory c WHERE a.payrollRunItem.id = :itemId AND c.sectionCode = :sectionCode")
    List<PayrollAdjustment> findByItemIdAndSectionCode(@Param("itemId") Long itemId, @Param("sectionCode") String sectionCode);
}
