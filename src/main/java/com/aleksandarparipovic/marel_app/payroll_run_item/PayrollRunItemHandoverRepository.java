package com.aleksandarparipovic.marel_app.payroll_run_item;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads and appends handovers. There is no update or delete: the table refuses
 * both at the database level, so offering them here would only produce a
 * runtime error at a call site that believed it could.
 */
@Repository
public interface PayrollRunItemHandoverRepository extends JpaRepository<PayrollRunItemHandover, Long> {

    /** Newest first — the order the screen reads them in. */
    List<PayrollRunItemHandover> findByPayrollRunItemIdOrderByOccurredAtDesc(Long payrollRunItemId);
}
