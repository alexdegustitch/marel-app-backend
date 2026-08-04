package com.aleksandarparipovic.marel_app.payroll_time_adjustment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PayrollTimeAdjustmentRepository extends JpaRepository<PayrollTimeAdjustment, Long> {

    @Query("""
        SELECT t FROM PayrollTimeAdjustment t
        JOIN FETCH t.category
        WHERE t.payrollRunItem.id = :itemId
          AND t.archivedAt IS NULL
        ORDER BY t.category.sortOrder, t.id
        """)
    List<PayrollTimeAdjustment> findByItemIdWithCategory(@Param("itemId") Long itemId);

    /**
     * The payable correction for one item, as a single number.
     *
     * <p>COALESCE because an item with no corrections must read 0, not null —
     * a null here would propagate into the minutes total and turn "nothing was
     * corrected" into "no minutes at all".
     */
    @Query("""
        SELECT COALESCE(SUM(t.minutes), 0) FROM PayrollTimeAdjustment t
        WHERE t.payrollRunItem.id = :itemId
          AND t.isApplied = true
          AND t.archivedAt IS NULL
          AND t.category.impactCode = 'PAYABLE_MINUTES'
        """)
    int sumPayableMinutesFor(@Param("itemId") Long itemId);

    /**
     * The same sum for many items at once.
     *
     * <p>ONE QUERY FOR A WHOLE RUN. The single-item version in a loop is what
     * PayrollSchemeScopeBatchingIT exists to prevent elsewhere: a 300-person
     * factory's payroll screen is 300 rows, and this figure is on every one of
     * them.
     *
     * <p>Items with no correction are simply absent from the result — GROUP BY
     * cannot invent a row for them — so the caller must default to 0 rather than
     * expect an entry per id.
     */
    @Query("""
        SELECT t.payrollRunItem.id, COALESCE(SUM(t.minutes), 0)
        FROM PayrollTimeAdjustment t
        WHERE t.payrollRunItem.id IN :itemIds
          AND t.isApplied = true
          AND t.archivedAt IS NULL
          AND t.category.impactCode = 'PAYABLE_MINUTES'
        GROUP BY t.payrollRunItem.id
        """)
    List<Object[]> sumPayableMinutesByItem(@Param("itemIds") Collection<Long> itemIds);
}
