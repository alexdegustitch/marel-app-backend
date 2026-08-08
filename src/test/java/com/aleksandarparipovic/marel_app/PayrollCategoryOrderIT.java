package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRun;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRunRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payroll category rows come back in the order the categories are configured to
 * appear in.
 *
 * <p>Worth a real test rather than an eyeballed ORDER BY: the payroll screen and
 * the PDF both render this list directly and neither re-sorts, so the ordering
 * lives entirely in the query. Without it the sequence is whatever PostgreSQL
 * happens to return — stable enough to look deliberate, and then different after
 * an update rewrites the pages.
 */
@Transactional
class PayrollCategoryOrderIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemRepository payrollRunItemRepository;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private PayrollRunItemCategoryRepository itemCategoryRepository;
    @Autowired private PayrollRunRepository payrollRunRepository;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private WorkCodeCategory category(String suffix, int displayOrder) {
        int n = COUNTER.incrementAndGet();
        return categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-ORD-" + suffix + "-" + n).categoryName("Kategorija " + suffix)
                .type("WORK").isPaid(true).normMultiplier(1.0d).isActive(true)
                .fixedHourlyRate(false).affectsMealAllowance(true).allowsParallelWork(false)
                .displayOrder(displayOrder).baseCategory(false).build());
    }

    private PayrollRunItem anItem() {
        int n = COUNTER.incrementAndGet();

        Department department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.saveAndFlush(
                        Department.builder().name("IT-DEPT-" + n).active(true).build()));

        Employee employee = employeeRepository.saveAndFlush(Employee.builder()
                .department(department).firstName("Order").lastName("Employee " + n).employeeNo("IT-ORD-EMP-" + n)
                .employmentStartDate(LocalDate.of(2020, 1, 1)).active(true)
                .normGraceDays(30).transportAllowanceMode("AUTO")
                .preferredLocale("sr-Latn").build());

        PayrollRun run = new PayrollRun();
        run.setReportYear(2030);
        run.setReportMonth(1 + (n % 12));
        run.setRunCode("IT-RUN-" + n);
        run.setStatus("DRAFT");
        run.setCreatedAt(OffsetDateTime.now());
        run = payrollRunRepository.saveAndFlush(run);

        // Inserted natively with only the three columns that have no database
        // default, letting the schema fill the rest.
        //
        // Not through PayrollRunItemService.create: that path does not set
        // adjustment_amount and dies on its NOT NULL constraint — a pre-existing
        // bug in an endpoint this test has no business fixing. Native insert also
        // keeps the test about ORDERING rather than about entity defaults.
        Object id = entityManager.createNativeQuery("""
                        INSERT INTO payroll_run_items (payroll_run_id, employee_id)
                        VALUES (:runId, :employeeId)
                        RETURNING id
                        """)
                .setParameter("runId", run.getId())
                .setParameter("employeeId", employee.getId())
                .getSingleResult();

        entityManager.flush();
        entityManager.clear();
        return payrollRunItemRepository.findById(((Number) id).longValue()).orElseThrow();
    }

    private void addCategoryRow(PayrollRunItem item, WorkCodeCategory category) {
        PayrollRunItemCategory row = new PayrollRunItemCategory();
        row.setPayrollRunItem(item);
        row.setWorkCodeCategory(category);
        row.setSourceType("WORK");
        row.setTotalMinutes(0);
        row.setTotalPaidMinutes(0);
        row.setTotalQuantity(0);
        row.setTotalScrap(0);
        row.setWeightedNormMinutes(BigDecimal.ZERO);
        row.setCategoryCoefficientSnapshot(BigDecimal.ONE);
        row.setEffectiveMinutes(BigDecimal.ZERO);
        row.setHourlyRate(BigDecimal.ZERO);
        row.setAmount(BigDecimal.ZERO);
        row.setCreatedAt(OffsetDateTime.now());
        itemCategoryRepository.saveAndFlush(row);
    }

    @Test
    @DisplayName("rows come back by display order, not by the order they were inserted")
    void rowsAreOrderedByDisplayOrder() {
        PayrollRunItem item = anItem();

        WorkCodeCategory last = category("LAST", 300);
        WorkCodeCategory first = category("FIRST", 100);
        WorkCodeCategory middle = category("MIDDLE", 200);

        // Deliberately inserted in the wrong order — insertion order is exactly
        // what must NOT decide the result.
        addCategoryRow(item, last);
        addCategoryRow(item, first);
        addCategoryRow(item, middle);

        List<PayrollRunItemCategory> rows =
                itemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(item.getId());

        assertThat(rows)
                .extracting(r -> r.getWorkCodeCategory().getCategoryNo())
                .containsExactly(first.getCategoryNo(), middle.getCategoryNo(), last.getCategoryNo());
    }

    @Test
    @DisplayName("categories sharing a display order still come back in a stable order")
    void tiesAreBrokenDeterministically() {
        PayrollRunItem item = anItem();

        // display_order is unique in the current data, but nothing enforces that.
        // A tie must not leave the sequence to the database.
        WorkCodeCategory a = category("TIE-A", 500);
        WorkCodeCategory b = category("TIE-B", 500);

        addCategoryRow(item, b);
        addCategoryRow(item, a);

        List<PayrollRunItemCategory> rows =
                itemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(item.getId());

        assertThat(rows)
                .extracting(r -> r.getWorkCodeCategory().getId())
                .as("id breaks the tie, so the order is total")
                .containsExactly(a.getId(), b.getId());
    }
}
