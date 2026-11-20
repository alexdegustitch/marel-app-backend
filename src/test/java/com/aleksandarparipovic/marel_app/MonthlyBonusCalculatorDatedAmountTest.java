package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryAmountPeriod;
import com.aleksandarparipovic.marel_app.bonus.BonusCategoryAmountPeriodRepository;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRuleRepository;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRule;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonusRepository;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentContext;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentResult;
import com.aleksandarparipovic.marel_app.payroll_calculation.calculators.MonthlyBonusCalculator;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The base bonus is priced by the amount IN FORCE ON THE PAYROLL MONTH (V69),
 * not by whatever the category's column says today. The category column is
 * only the fallback for a month no spell covers (pre-seed data).
 */
@ExtendWith(MockitoExtension.class)
class MonthlyBonusCalculatorDatedAmountTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    @Mock private EmployeeBonusRepository employeeBonusRepository;
    @Mock private BonusMinHoursRuleRepository minHoursRuleRepository;
    @Mock private BonusEligibilityRuleRepository eligibilityRuleRepository;
    @Mock private BonusCategoryAmountPeriodRepository amountPeriodRepository;
    @InjectMocks private MonthlyBonusCalculator calculator;

    private ComponentContext octoberContext() {
        Employee employee = mock(Employee.class);
        lenient().when(employee.getId()).thenReturn(7L);
        PayrollRunItem item = mock(PayrollRunItem.class);
        lenient().when(item.getEmployee()).thenReturn(employee);
        lenient().when(item.getManualAdjustedMinutes()).thenReturn(0);
        MonthlyReport report = mock(MonthlyReport.class);
        // 160 h — comfortably above the 100 h threshold stubbed below.
        lenient().when(report.getMonthlyBonusEligibleMinutes()).thenReturn(160 * 60);
        return new ComponentContext(item, report, OCTOBER, LocalDate.of(2026, 10, 31),
                Map.of(), Map.of(), Set.of());
    }

    private void stubEarnedBase(BigDecimal categoryColumnAmount) {
        BonusMinHoursRule rule = mock(BonusMinHoursRule.class);
        when(rule.getEffectiveMinNumHours()).thenReturn(100);
        lenient().when(rule.getManualMinNumHours()).thenReturn(null);
        when(minHoursRuleRepository.findByPeriodAndArchivedAtIsNull(OCTOBER))
                .thenReturn(Optional.of(rule));

        BonusCategory category = BonusCategory.builder()
                .id(3L)
                .categoryNo("B1")
                .categoryName("Bonus I")
                .bonusAmount(categoryColumnAmount)
                .validFrom(LocalDate.of(2024, 1, 1))
                .build();
        EmployeeBonus employeeBonus = mock(EmployeeBonus.class);
        when(employeeBonus.getBonusCategory()).thenReturn(category);
        when(employeeBonusRepository.findInForce(7L, OCTOBER))
                .thenReturn(Optional.of(employeeBonus));

        when(eligibilityRuleRepository.findByPeriodAndArchivedAtIsNullOrderByMinNumHoursAsc(any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("October pays October's spell, not the column edited since")
    void monthReadsItsOwnSpell() {
        stubEarnedBase(new BigDecimal("7500")); // the column already raised
        BonusCategoryAmountPeriod octoberSpell = BonusCategoryAmountPeriod.builder()
                .bonusCategory(BonusCategory.builder().id(3L).build())
                .amount(new BigDecimal("6000"))
                .validFrom(LocalDate.of(2024, 1, 1))
                .validTo(LocalDate.of(2026, 10, 31))
                .build();
        when(amountPeriodRepository.findInForce(3L, OCTOBER))
                .thenReturn(Optional.of(octoberSpell));

        ComponentResult result = calculator.calculate(octoberContext());

        assertThat(result.systemAmount()).isEqualByComparingTo("6000");
        assertThat(result.inputs().get("baseBonusAmountSource").toString())
                .startsWith("AMOUNT_PERIOD_FROM_");
    }

    @Test
    @DisplayName("a month no spell covers falls back to the category column")
    void preSeedMonthFallsBack() {
        stubEarnedBase(new BigDecimal("7500"));
        when(amountPeriodRepository.findInForce(3L, OCTOBER)).thenReturn(Optional.empty());

        ComponentResult result = calculator.calculate(octoberContext());

        assertThat(result.systemAmount()).isEqualByComparingTo("7500");
        assertThat(result.inputs().get("baseBonusAmountSource")).isEqualTo("CATEGORY_FALLBACK");
    }
}
