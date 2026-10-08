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

    /**
     * Whether any adjustment still references a master category.
     *
     * <p>Checked before deleting the category so the caller gets a clear business
     * error instead of the raw RESTRICT constraint violation.
     */
    boolean existsByPayrollAdjustmentCategory_Id(Long payrollAdjustmentCategoryId);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory c JOIN FETCH c.section WHERE a.payrollRunItem.id = :itemId")
    List<PayrollAdjustment> findByPayrollRunItemIdWithCategory(@Param("itemId") Long itemId);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory")
    List<PayrollAdjustment> findAllWithCategory();

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory WHERE a.id = :id")
    Optional<PayrollAdjustment> findByIdWithCategory(@Param("id") Long id);

    // Both per-category lookups exclude CUSTOM rows, and must keep doing so:
    // a hand-added line may repeat a category (V59 made the unique index
    // partial), so without the filter a second "Isplaćeno" would make these
    // Optionals non-unique — and worse, let a calculator write over a figure a
    // person typed. The system's own row is the only one a category addresses.
    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory c WHERE a.payrollRunItem.id = :itemId AND c.calculationKey = :calculationKey AND a.isCustom = false")
    Optional<PayrollAdjustment> findByItemIdAndCalculationKey(@Param("itemId") Long itemId, @Param("calculationKey") String calculationKey);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory c WHERE a.payrollRunItem.id = :itemId AND c.code = :code AND a.isCustom = false")
    Optional<PayrollAdjustment> findByItemIdAndCategoryCode(@Param("itemId") Long itemId, @Param("code") String code);

    @Query("SELECT a FROM PayrollAdjustment a JOIN FETCH a.payrollAdjustmentCategory c JOIN FETCH c.section s WHERE a.payrollRunItem.id = :itemId AND s.code = :sectionCode")
    List<PayrollAdjustment> findByItemIdAndSectionCode(@Param("itemId") Long itemId, @Param("sectionCode") String sectionCode);
}
