package com.aleksandarparipovic.marel_app.department_head;

import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fills in the employee table columns that the projection query cannot select.
 *
 * <p>WHY THIS IS NOT PART OF THE QUERY. It belongs in the projection beside
 * every other column, and that is where it was first written — as a correlated
 * subquery in the SELECT. Hibernate 6 cannot render one inside a constructor
 * select: entity equality against the outer root gives "Could not locate
 * TableGroup", {@code correlate()} plus a join gives "Already registered a
 * copy", and comparing the foreign key directly gives the first error again.
 *
 * <p>So it is resolved separately — ONE query per page, not per row. The cost is
 * a second round trip; the alternative was a construct the ORM cannot express.
 *
 * <p>The current hourly rate joined it for a different reason: it does not live
 * on the employee at all. {@code employees.hourly_rate} is only a fallback, and
 * the value that is actually paid comes from the dated
 * {@code employee_payroll_value_history}. Resolving it here keeps the table and
 * the payslip reading the same number.
 */
@Component
@RequiredArgsConstructor
public class EmployeeRowEnricher {

    private final DepartmentHeadPeriodRepository repository;
    private final EmployeePayrollValueService payrollValueService;

    public Page<EmployeeWithBonusView> enrich(Page<EmployeeWithBonusView> page) {
        enrich(page.getContent());
        return page;
    }

    public Optional<EmployeeWithBonusView> enrich(Optional<EmployeeWithBonusView> row) {
        row.ifPresent(v -> enrich(List.of(v)));
        return row;
    }

    public void enrich(Collection<EmployeeWithBonusView> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<Long> employeeIds = rows.stream()
                .map(EmployeeWithBonusView::getEmployeeId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        if (employeeIds.isEmpty()) {
            return;
        }

        LocalDate today = LocalDate.now();

        Set<Long> heads = Set.copyOf(repository.findEmployeeIdsHeadingOn(employeeIds, today));
        Map<Long, Map<String, BigDecimal>> values = payrollValueService.numericValuesOn(employeeIds, today);

        rows.forEach(row -> {
            row.setDepartmentHead(heads.contains(row.getEmployeeId()));

            // Same precedence as PayrollRunItemService.hourlyRateFor: the dated
            // value wins, the column is only what is left when there is none.
            BigDecimal dated = values.getOrDefault(row.getEmployeeId(), Map.of())
                    .get(EmployeePayrollValueCodes.HOURLY_RATE);
            row.setCurrentHourlyRate(dated != null ? dated : row.getHourlyRate());
        });
    }
}
