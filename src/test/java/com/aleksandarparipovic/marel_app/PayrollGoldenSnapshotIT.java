package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentService;
import com.aleksandarparipovic.marel_app.payroll_adjustment.dto.PayrollAdjustmentCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_calculation.PayrollCalculatorRegistry;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollAdjustmentDetailDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemDetailResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.AdjustmentPatchDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The numbers payroll produces TODAY, frozen.
 *
 * <p>This is the safety net for the component migration described in
 * {@code docs/business-rules/payroll-component-migration-plan.md}. Every phase of
 * that plan rewrites part of the arithmetic in {@code PayrollRunItemService}, and
 * the only way to tell a refactor from a silent pay change is to have the current
 * result written down first.
 *
 * <p><b>How to treat a failure.</b> A red test here is not a test to fix. It is
 * either a real regression, or a value the plan says must change — and in the
 * second case the expected number is updated <em>in the same commit</em>, with a
 * comment naming the phase and the reason. Never adjust an expectation to make
 * the suite green.
 *
 * <p><b>Disabled tests are part of the specification.</b> Several behaviours the
 * plan requires do not exist yet. They are written here now, disabled, with the
 * phase that enables them, so the list is visible in the suite rather than
 * remembered.
 */
@Transactional
class PayrollGoldenSnapshotIT extends AbstractIntegrationTest {

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollRunItemCategoryRepository itemCategoryRepository;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    /** The patch DTO has no setter for the minute fields; the controller uses Jackson. */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    @Autowired private PayrollAdjustmentCategoryRepository adjustmentCategoryRepository;
    @Autowired private EmployeePayrollValueService valueService;
    @Autowired private PayrollCalculatorRegistry calculatorRegistry;
    @Autowired private PayrollAdjustmentService payrollAdjustmentService;
    @Autowired private EntityManager entityManager;

    /** The exact rule from decision D3, run as the calculator will run it in phase 3. */
    private static final String TRANSPORT_UNITS_SQL = """
            SELECT count(*)
            FROM daily_reports dr
            WHERE dr.employee_id = :employeeId
              AND dr.work_date BETWEEN :periodStart AND :periodEnd
              AND dr.total_work_minutes > 0
              AND dr.archived_at IS NULL
            """;

    /**
     * The transport line's system figures.
     *
     * <p>These used to be asserted on payroll_run_items.transport_allowance_days
     * and .transport_allowance_unit_amount. Nothing read those columns and they
     * were dropped by 2026-08-31-01 — the count and the unit price live on the
     * TRANSPORT_ALLOWANCE row, which is where the payslip reads them.
     */
    private PayrollAdjustment transportLine(PayrollRunItem item) {
        return adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE")
                .orElseThrow();
    }

    /** The line exists and the scheme refuses the category — the production shape. */
    private void denyBonusForThisEmployee(PayrollScenarioFixture.Scenario scenario) {
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_category_scheme_rules r
                SET is_allowed = FALSE
                FROM payroll_adjustment_categories c, employee_compensation_scheme_history h
                WHERE c.id = r.payroll_adjustment_category_id
                  AND c.code = 'MONTHLY_BONUS'
                  AND h.employee_id = :emp AND h.archived_at IS NULL
                  AND r.compensation_scheme_id = h.compensation_scheme_id""")
                .setParameter("emp", scenario.employee().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Edit a figure ON ITS LINE — the route the parameters panel now uses.
     *
     * <p>These tests used to set payroll_run_items fields on the patch request.
     * Those fields are gone: the meal price and the transport total are edited
     * through the adjustments array, because the line is what the calculation
     * reads.
     */
    private static final String CAT_TRANSPORT = "TRANSPORT_ALLOWANCE";
    private static final String CAT_BONUS = "MONTHLY_BONUS";

    private void patchLine(Long itemId, String code, java.util.function.Consumer<AdjustmentPatchDto> fill) {
        AdjustmentPatchDto dto = new AdjustmentPatchDto();
        dto.setId(adjustmentRepository.findByItemIdAndCategoryCode(itemId, code).orElseThrow().getId());
        fill.accept(dto);
        PayrollRunItemPatchRequest request = new PayrollRunItemPatchRequest();
        request.setAdjustments(List.of(dto));
        payrollRunItemService.patch(itemId, request);
    }

    private PayrollRunItem calculate(PayrollScenarioFixture.Scenario scenario) {
        return payrollRunItemService.getForPayrollAccess(scenario.item().getId());
    }

    private PayrollRunItemCategory categoryRow(PayrollRunItem item, WorkCodeCategory category) {
        return itemCategoryRepository.findByPayrollRunItemIdWithWorkCodeCategory(item.getId()).stream()
                .filter(row -> row.getWorkCodeCategory().getId().equals(category.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No payroll row for category " + category.getCategoryNo()));
    }

    private List<String> visibleAdjustmentCodes(PayrollRunItemDetailResponse response) {
        return response.getAdjustments().stream()
                .flatMap(section -> section.getAdjustments().stream())
                .map(PayrollAdjustmentDetailDto::getCategoryCode)
                .toList();
    }

    /** Read straight from the database, so a stale managed instance cannot mask a divergence. */
    private BigDecimal mealAdjustmentAmount(Long itemId) {
        return adjustmentRepository.findByItemIdAndCategoryCode(itemId, "MEAL_ALLOWANCE")
                .orElseThrow(() -> new AssertionError("No MEAL_ALLOWANCE adjustment on item " + itemId))
                .getAmount();
    }

    private long transportUnits(Employee employee, LocalDate from, LocalDate to) {
        return ((Number) entityManager.createNativeQuery(TRANSPORT_UNITS_SQL)
                .setParameter("employeeId", employee.getId())
                .setParameter("periodStart", from)
                .setParameter("periodEnd", to)
                .getSingleResult()).longValue();
    }

    // ═══ 1. Standard production employee ════════════════════════════════════

    /** A line's figure, or zero when the scheme excludes the category. */
    private java.math.BigDecimal lineAmount(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getAmount()).orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineSystemAmount(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getSystemAmount()).orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineSystemUnit(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getSystemUnitAmount()).orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineUnit(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getUnitAmount() != null ? a.getUnitAmount() : a.getSystemUnitAmount())
                .orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal lineQuantity(PayrollRunItem item, String code) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), code)
                .map(a -> a.getSystemQuantity()).orElse(java.math.BigDecimal.ZERO);
    }

    // ── The bonus's two parts, off the line ─────────────────────────────────
    //
    // The line keeps `amount` as the effective TOTAL and `correction_amount` as
    // the tier, so the base is the difference. Nine item columns used to mirror
    // this and these assertions used to read them; the columns are gone.

    private java.math.BigDecimal bonusTotal(PayrollRunItem item) {
        return lineAmount(item, CAT_BONUS);
    }

    private java.math.BigDecimal bonusBase(PayrollRunItem item) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), CAT_BONUS)
                .map(a -> zeroIfNull(a.getAmount()).subtract(zeroIfNull(a.getCorrectionAmount())))
                .orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal bonusSystemBase(PayrollRunItem item) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), CAT_BONUS)
                .map(a -> zeroIfNull(a.getSystemAmount()).subtract(zeroIfNull(a.getSystemCorrectionAmount())))
                .orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal bonusAdditional(PayrollRunItem item) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), CAT_BONUS)
                .map(a -> zeroIfNull(a.getCorrectionAmount())).orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal bonusSystemAdditional(PayrollRunItem item) {
        return adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), CAT_BONUS)
                .map(a -> zeroIfNull(a.getSystemCorrectionAmount())).orElse(java.math.BigDecimal.ZERO);
    }

    private static java.math.BigDecimal zeroIfNull(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    /** Edit one part of the bonus the way the parameters panel does. */
    private void patchBonusBase(Long itemId, String amount) {
        patchLine(itemId, CAT_BONUS, d -> d.setBaseAmount(new BigDecimal(amount)));
    }

    private void patchBonusAdditional(Long itemId, String amount) {
        patchLine(itemId, CAT_BONUS, d -> d.setCorrectionAmount(new BigDecimal(amount)));
    }

    @Test
    @DisplayName("1. standard production employee — the whole arithmetic, pinned")
    void standardProductionEmployee() {
        var scenario = fixture.scenario().build();

        PayrollRunItem item = calculate(scenario);

        // Operational totals copied from the monthly report.
        assertThat(item.getTotalWorkMinutes()).isEqualTo(10_560);
        assertThat(item.getTotalPayrollMinutes()).isEqualTo(10_560);
        assertThat(item.getTotalEffectiveMinutes()).isEqualByComparingTo("10560.00");

        // Hourly rate refreshed from the employee, no override in play.
        assertThat(item.getHourlyRateSystem()).isEqualByComparingTo("420.00");
        assertThat(item.getHourlyRate()).isEqualByComparingTo("420.00");
        assertThat(item.getHourlyRateOverridden()).isFalse();

        // Category: effectiveMinutes = weightedNorm x coefficient,
        //           amount = effectiveMinutes / 60 x hourlyRate.
        PayrollRunItemCategory row = categoryRow(item, scenario.workCategory());
        assertThat(row.getEffectiveMinutes()).isEqualByComparingTo("10560.00");
        assertThat(row.getHourlyRate()).isEqualByComparingTo("420.00");
        assertThat(row.getAmount()).isEqualByComparingTo("73920.00");
        // bonus on top = amount x performanceCoefficient, because the scheme allows it
        assertThat(row.getBonusAmount()).isEqualByComparingTo("7392.00");

        // Meal: count from the monthly report, rate from app_settings.
        assertThat(lineQuantity(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("20");
        assertThat(lineSystemUnit(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("300.00");
        assertThat(lineAmount(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("6000.00");

        // TRANSPORT IS ZERO, AND SINCE PHASE 3 IT IS ZERO FOR A DIFFERENT REASON.
        //
        // Before: transport_allowance_days was initialised to 0 and never computed,
        // so the amount was 0 x the global app_settings rate, which the item still
        // displayed as 350.
        //
        // Now: the unit price is the EMPLOYEE'S own TRANSPORT_RATE, because
        // transport is paid to some people and not others at rates that differ per
        // person — a single global figure could never express that, which is why
        // the whole line was structurally dead. This employee has no rate in force,
        // so the price is 0 and so is the amount.
        //
        // 350.00 -> 0.00 is therefore a deliberate phase 3 change, not a regression.
        assertThat(transportLine(item).getSystemQuantity()).isEqualByComparingTo("0");
        // No unit price to assert: with no rate in force the calculator returns a
        // zero, not a quantity times a price, and system_unit_amount is null. The
        // dropped column defaulted that null to 0.00, which is part of why it said
        // nothing.
        assertThat(transportLine(item).getSystemAmount()).isEqualByComparingTo("0.00");
        assertThat(lineSystemAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("0.00");
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("0.00");

        // Summary: categories + meal + transport + applied ADDITIONS.
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("79920.00");
        assertThat(item.getPreviouslyPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(item.getCurrentBalanceAmount()).isEqualByComparingTo("79920.00");
        assertThat(item.getNetPayableAmount()).isEqualByComparingTo("79920.00");

        // total_gross_earnings is never computed by any code path — it stays 0.
        // Phase 7 either computes it or drops it; a column that always reads 0 is
        // recorded here so the decision is not forgotten.
    }

    @Test
    @DisplayName("1b. every adjustment line reaches exactly the total its SECTION routes it to")
    void sectionCodeRoutesMoneyNotImpactCode() {
        var scenario = fixture.scenario().build();

        // Distinguishable amounts, one per section, so a line that lands in the
        // wrong total is arithmetically visible rather than hidden behind zeros.
        fixture.adjustmentAmount(scenario, "FIXED_SALARY", "10000.00");                  // ADDITIONS
        fixture.adjustmentAmount(scenario, "OTHER", "1000.00");                          // ADDITIONS
        fixture.adjustmentAmount(scenario, "POSITIVE_NEGATIVE_CORRECTION", "-500.00");   // ADDITIONS, negative
        fixture.adjustmentAmount(scenario, "INSTALLMENT", "2000.00");                    // SETTLEMENTS
        fixture.adjustmentAmount(scenario, "PHONE_PREVIOUS_MONTH", "700.00");            // SETTLEMENTS
        fixture.adjustmentAmount(scenario, "PAID_PART_1", "30000.00");                   // SETTLEMENTS
        fixture.adjustmentAmount(scenario, "PAID_PART_2", "20000.00");                   // SETTLEMENTS
        fixture.adjustmentAmount(scenario, "PHONE_CURRENT_MONTH", "800.00");             // PHONE
        fixture.adjustmentAmount(scenario, "PAID_PREVIOUS_PERIOD", "5000.00");           // SETTLEMENTS_SUM
        fixture.adjustmentAmount(scenario, "PREVIOUS_BALANCE", "-1500.00");              // BALANCE

        PayrollRunItem item = calculate(scenario);

        // ADDITIONS: 10000 + 1000 - 500 = 10500, on top of work + meal.
        // MONTHLY_BONUS is deliberately not among them: since the bonus became a
        // calculated line it is derived from the bonus rules, and a figure typed
        // straight onto the adjustment would just be recomputed away.
        // TRANSPORT_ALLOWANCE is in ADDITIONS too but is excluded by CODE and
        // added from the item column instead — the double-bookkeeping phase 4 ends.
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("90420.00");

        // SETTLEMENTS: 2000 + 700 + 30000 + 20000 = 52700.
        //
        // NOT 56200. PAID_PREVIOUS_PERIOD (5000) sits in SETTLEMENTS_SUM and
        // PREVIOUS_BALANCE (-1500) in BALANCE, and previouslyPaid filters on the
        // literal string "SETTLEMENTS". Both are display mirrors: the balance is
        // actually carried by previous_net_payable_amount. Phase 4 replaces this
        // section filter with impact codes and MUST keep them out of the sum.
        assertThat(item.getPreviouslyPaidAmount()).isEqualByComparingTo("52700.00");

        // THE CURRENT MONTH'S PHONE REACHES NO TOTAL, and that is the fact worth
        // pinning. total_deductions_amount used to sum every DEDUCTION_MINUS line
        // — 2000 + 800 + 700 + 20000 = 23500 — which put this 800 phone, charged
        // next month rather than this one, and PAID_PART_2's 20 000, money already
        // paid OUT, beside the two real deductions. The column is dropped; what it
        // was hiding is asserted directly instead. The phone sits on the item and
        // moves neither balance below; it is charged next month as
        // PHONE_PREVIOUS_MONTH (OPEN-12).
        assertThat(lineAmount(item, "PHONE_CURRENT_MONTH")).isEqualByComparingTo("800.00");

        assertThat(item.getCurrentBalanceAmount()).isEqualByComparingTo("37720.00");
        assertThat(item.getNetPayableAmount()).isEqualByComparingTo("37720.00");

        // The two lines that are booked twice, still agreeing. Phase 4a's
        // dual-write comparison is exactly this assertion, run over real data.
        assertThat(scenario.adjustment("MEAL_ALLOWANCE").getAmount())
                .isEqualByComparingTo(lineAmount(item, "MEAL_ALLOWANCE"));
        assertThat(scenario.adjustment("TRANSPORT_ALLOWANCE").getAmount())
                .isEqualByComparingTo(lineAmount(item, "TRANSPORT_ALLOWANCE"));
    }

    // ═══ 2. Foreign employee ════════════════════════════════════════════════

    @Test
    @DisplayName("2. foreign employee — no meal, no transport, no bonus, and no line on the payslip")
    void foreignEmployee() {
        var scenario = fixture.scenario()
                .scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT)
                .foreigner(true)
                .denyAdjustment("MEAL_ALLOWANCE", "TRANSPORT_ALLOWANCE")
                .build();

        PayrollRunItem item = calculate(scenario);

        // Zeroed on the ITEM, not merely unlinked: totalNetEarnings adds these two
        // columns directly, so suppressing only the adjustment row would take the
        // line off the payslip while still paying the money.
        assertThat(lineQuantity(item, "MEAL_ALLOWANCE")).isZero();
        assertThat(lineAmount(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("0.00");
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("0.00");
        // The scheme excludes transport, so there is no line at all — which is a
        // stronger statement than a line reading zero.
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE")).isEmpty();

        // The work itself is paid exactly as for anyone else.
        PayrollRunItemCategory row = categoryRow(item, scenario.workCategory());
        assertThat(row.getAmount()).isEqualByComparingTo("73920.00");

        // allows_performance_bonus = false removes the bonus ON TOP. Efficiency is
        // untouched: it already weighted the minutes that became the amount above.
        assertThat(row.getBonusAmount()).isEqualByComparingTo("0.00");

        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("73920.00");
        assertThat(item.getNetPayableAmount()).isEqualByComparingTo("73920.00");

        // Not on the document either.
        var details = payrollRunItemService.getDetails(scenario.monthlyReport().getId());
        assertThat(visibleAdjustmentCodes(details))
                .doesNotContain("MEAL_ALLOWANCE", "TRANSPORT_ALLOWANCE")
                .contains("FIXED_SALARY", "MONTHLY_BONUS");
    }

    // ═══ 3. Commercial — bonus visible, always zero ═════════════════════════

    @Test
    @DisplayName("3. commercial employee — bonus line is printed and is zero")
    void commercialEmployeeShowsAZeroBonus() {
        fixture.ensureScheme("IT-COMMERCIAL", "Komercijala", true, false);

        var scenario = fixture.scenario()
                .scheme("IT-COMMERCIAL")
                .commercial(true)
                .build();

        PayrollRunItem item = calculate(scenario);

        // No bonus by the hour — the scheme says so, nothing reads works_in_commercial.
        PayrollRunItemCategory row = categoryRow(item, scenario.workCategory());
        assertThat(row.getBonusAmount()).isEqualByComparingTo("0.00");

        // Meal and transport behave normally: only the bonus is switched off.
        assertThat(lineAmount(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("6000.00");
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("79920.00");

        // The line still exists and is still shown, at zero. This is the whole
        // difference from the foreign case: excluded vs included-but-zero.
        var details = payrollRunItemService.getDetails(scenario.monthlyReport().getId());
        assertThat(visibleAdjustmentCodes(details)).contains("MONTHLY_BONUS");
        assertThat(scenario.adjustment("MONTHLY_BONUS").getAmount()).isEqualByComparingTo("0.00");
    }

    // ═══ 4. A brand-new scheme is data only ═════════════════════════════════

    @Test
    @DisplayName("4. a seasonal scheme invented in the test calculates with no code change")
    void seasonalSchemeNeedsNoCode() {
        fixture.ensureScheme("IT-SEASONAL", "Sezonski rad", true, false);

        var scenario = fixture.scenario()
                .scheme("IT-SEASONAL")
                .denyAdjustment("MONTHLY_BONUS")
                .build();

        PayrollRunItem item = calculate(scenario);

        assertThat(lineAmount(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("6000.00");
        assertThat(categoryRow(item, scenario.workCategory()).getBonusAmount()).isEqualByComparingTo("0.00");
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("79920.00");

        var details = payrollRunItemService.getDetails(scenario.monthlyReport().getId());
        assertThat(visibleAdjustmentCodes(details))
                .doesNotContain("MONTHLY_BONUS")
                .contains("MEAL_ALLOWANCE");
    }

    // ═══ 5. Override ════════════════════════════════════════════════════════

    @Test
    @DisplayName("5. overriding the meal unit price recomputes the total and flags the override")
    void mealUnitPriceOverride() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        patchLine(scenario.item().getId(), "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("350.00")));

        // Asserted on the LINE, which is the source. The payroll_run_items mirror
        // columns lag until the next full recalculation and are dropped in phase 7;
        // asserting them would be testing a copy on its way out.
        PayrollAdjustment line = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MEAL_ALLOWANCE").orElseThrow();
        assertThat(line.getSystemUnitAmount()).isEqualByComparingTo("300.00");
        assertThat(line.getUnitAmount()).isEqualByComparingTo("350.00");
        assertThat(line.getAmount()).isEqualByComparingTo("7000.00");

        // Editing a permitted INPUT is not a hard override of the total (D7), and
        // has_manual_input is what records that a person set the price.
        assertThat(line.getIsOverridden()).isFalse();
        assertThat(line.getHasManualInput()).isTrue();

        PayrollRunItem item = payrollRunItemService.findById(scenario.item().getId());
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("80920.00");
        assertThat(item.getNetPayableAmount()).isEqualByComparingTo("80920.00");
    }

    @Test
    @DisplayName("5b. a recalculation keeps an overridden unit price")
    void overrideSurvivesRecalculation() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        patchLine(scenario.item().getId(), "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("350.00")));

        // Force the version check to recalculate.
        PayrollRunItem stale = payrollRunItemService.findById(scenario.item().getId());
        stale.setNeedsRecalculation(true);
        entityManager.flush();

        PayrollRunItem item = calculate(scenario);

        assertThat(lineUnit(item, "MEAL_ALLOWANCE"))
                .as("the system rate must not overwrite a human decision")
                .isEqualByComparingTo("350.00");
        assertThat(lineAmount(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("7000.00");
    }

    @Test
    @DisplayName("5c. patching the meal price moves the adjustment row immediately (F11, fixed in phase 4)")
    void mealPatchSyncsTheAdjustmentRow() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        calculate(scenario);

        assertThat(mealAdjustmentAmount(itemId)).isEqualByComparingTo("6000.00");

        patchLine(itemId, "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("350.00")));

        // BEFORE PHASE 4 THE TWO BOOKS DISAGREED HERE. patch step 2 recomputed the
        // item column and never touched the adjustment, which stayed at 6000.00
        // until some later recalculation happened to fix it (finding F11). That was
        // survivable only while the row reached no total.
        //
        // Now the row IS the total, and the edit goes straight to it: the line
        // applies its own formula — count × price + correction — the moment the
        // price changes, rather than waiting for a recalculation to notice.
        assertThat(mealAdjustmentAmount(itemId)).isEqualByComparingTo("7000.00");
        assertThat(mealAdjustmentAmount(itemId))
                .as("the adjustment row is the source of truth and must move with the edit")
                .isEqualByComparingTo("7000.00");

        // And the money is in the total exactly once — 73920 work + 7000 meal.
        assertThat(payrollRunItemService.findById(itemId).getTotalNetEarnings())
                .isEqualByComparingTo("80920.00");

        // Editing a permitted INPUT is not a hard override of the total. The two
        // stay distinguishable, which is what D7 needs.
        assertThat(adjustmentRepository.findByItemIdAndCategoryCode(itemId, "MEAL_ALLOWANCE")
                .orElseThrow().getIsOverridden())
                .as("repricing a meal runs the formula; it does not bypass it")
                .isFalse();
    }

    @Test
    @DisplayName("5d. allow_override is FALSE on meal and transport, yet both are editable")
    void allowOverrideIsDecorativeForMealAndTransport() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        // The catalogue says these two may not be overridden...
        assertThat(adjustmentCategoryRepository.findByCode("MEAL_ALLOWANCE").orElseThrow()
                .getAllowOverride()).isFalse();
        assertThat(adjustmentCategoryRepository.findByCode("TRANSPORT_ALLOWANCE").orElseThrow()
                .getAllowOverride()).isFalse();

        // ...and the line-level route is what enforces it. The item-column route
        // that used to go round the flag is gone: the meal price and the transport
        // total are edited on their lines, where editable_input and
        // allow_total_override are read.

        // Meal: the PRICE is the input, so this is allowed and the formula still
        // multiplies it by the system's count.
        patchLine(scenario.item().getId(), "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("350.00")));

        // Meal: a typed TOTAL is not, because allow_total_override is false.
        assertThatThrownBy(() ->
                patchLine(scenario.item().getId(), "MEAL_ALLOWANCE", d -> {
                    d.setAmount(new BigDecimal("99999.00"));
                    d.setOverrideReason("Dogovoreno");
                }))
                .isInstanceOf(ConflictException.class);

        // Transport: a typed total IS allowed — and only with a reason.
        assertThatThrownBy(() ->
                patchLine(scenario.item().getId(), CAT_TRANSPORT, d -> d.setAmount(new BigDecimal("1234.00"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Razlog");

        patchLine(scenario.item().getId(), CAT_TRANSPORT, d -> {
            d.setAmount(new BigDecimal("1234.00"));
            d.setOverrideReason("Dogovoreno sa direktorom");
        });

        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MEAL_ALLOWANCE").orElseThrow()
                .getAmount()).isEqualByComparingTo("7000.00");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), CAT_TRANSPORT).orElseThrow()
                .getAmount()).isEqualByComparingTo("1234.00");
    }

    // ═══ 6. Remapped work-code category ═════════════════════════════════════

    @Test
    @DisplayName("6. a remapped source category gets no payroll row — only its target does")
    void remappedSourceCategoryIsNotPayable() {
        var scenario = fixture.scenario()
                .scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT)
                .foreigner(true)
                .build();

        // Booked on the source, paid on the target.
        WorkCodeCategory source = fixture.remap(
                CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, scenario.workCategory());

        PayrollRunItem item = calculate(scenario);

        List<Long> payrollCategoryIds = itemCategoryRepository
                .findByPayrollRunItemIdWithWorkCodeCategory(item.getId()).stream()
                .map(row -> row.getWorkCodeCategory().getId())
                .toList();

        assertThat(payrollCategoryIds)
                .as("nothing can accumulate against a remapped source, so a row for it would be a permanent zero")
                .doesNotContain(source.getId())
                .contains(scenario.workCategory().getId());
    }

    // ═══ 7-9. Transport counting rule (D3) ══════════════════════════════════

    /**
     * The rule the phase-3 calculator must implement, pinned against the real
     * {@code daily_reports} shape before the calculator exists. Phase 3 replaces
     * the raw SQL here with the repository method and these numbers must not move.
     */
    @Nested
    @DisplayName("transport units = distinct work shifts with work_minutes > 0")
    class TransportCounting {

        private final LocalDate from = LocalDate.of(2026, 9, 1);
        private final LocalDate to = LocalDate.of(2026, 9, 30);

        @Test
        @DisplayName("7. one shift with work minutes is one unit")
        void oneQualifyingShift() {
            var scenario = fixture.scenario().build();
            fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 480, 450);

            assertThat(transportUnits(scenario.employee(), from, to)).isEqualTo(1);
        }

        @Test
        @DisplayName("8. two different shifts on the SAME day are two units")
        void twoShiftsSameDay() {
            var scenario = fixture.scenario().build();
            LocalDate day = LocalDate.of(2026, 9, 3);
            fixture.dailyReport(scenario.employee(), day, 6, 480, 450);   // 06:00-14:00
            fixture.dailyReport(scenario.employee(), day, 16, 240, 200);  // 16:00-20:00

            // Counted per SHIFT RECORD, not per calendar day: someone who came in
            // twice travelled twice. The two must not overlap in wall-clock time —
            // ex_work_shifts_no_overlap makes an overlapping pair impossible to
            // record in the first place, so "two shifts" always means two arrivals.
            assertThat(transportUnits(scenario.employee(), from, to)).isEqualTo(2);
        }

        @Test
        @DisplayName("9. a shift with zero work minutes is not a unit")
        void shiftWithoutWorkMinutes() {
            var scenario = fixture.scenario().build();
            fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 480, 450);
            fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 4), 480, 0);

            assertThat(transportUnits(scenario.employee(), from, to)).isEqualTo(1);
        }

        @Test
        @DisplayName("9b. a full shift of absence is not a unit — total_work_minutes counts WORK only")
        void absenceOnlyShift() {
            var scenario = fixture.scenario().build();
            var report = fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 4), 480, 0);
            report.setTotalAbsencePaidMinutes(480);
            entityManager.flush();

            assertThat(transportUnits(scenario.employee(), from, to)).isZero();
        }

        @Test
        @DisplayName("9c. a shift crossing midnight is one record and one unit")
        void shiftAcrossMidnight() {
            var scenario = fixture.scenario().build();
            // 22:00 -> 06:00 the next morning. One work_shifts row carrying the day
            // it STARTED on, so one daily_reports row and one arrival — not two.
            fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 22, 480, 470);

            assertThat(transportUnits(scenario.employee(), from, to)).isEqualTo(1);
        }

        @Test
        @DisplayName("9d. several work logs in one shift stay one unit — enforced by the schema")
        void oneRowPerShiftIsEnforcedByTheDatabase() {
            // uq_daily_reports_employee_shift UNIQUE (employee_id, work_shift_id)
            // is what makes a plain count(*) equal count(DISTINCT work_shift_id).
            // If that constraint ever goes, the rule silently starts double-paying.
            Object present = entityManager.createNativeQuery("""
                    SELECT count(*) FROM pg_constraint
                    WHERE conname = 'uq_daily_reports_employee_shift'
                    """).getSingleResult();

            assertThat(((Number) present).intValue())
                    .as("the transport rule depends on one daily_reports row per shift")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("9e. shifts outside the payroll period are not counted")
        void shiftsOutsideThePeriod() {
            var scenario = fixture.scenario().build();
            fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 480, 450);
            fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 8, 31), 480, 450);
            fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 10, 1), 480, 450);

            assertThat(transportUnits(scenario.employee(), from, to)).isEqualTo(1);
        }
    }

    // ═══ 14. Hourly rate from the value history (phase 2) ═══════════════════

    @Test
    @DisplayName("14. the hourly rate comes from the value history, priced at the period")
    void hourlyRateComesFromTheValueHistory() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 3)).build();
        Long employeeId = scenario.employee().getId();

        // 400 until August, 500 from June... deliberately overlapping the fixture's
        // employees.hourly_rate of 420, so a fallback to the employee column would
        // produce a visibly different number.
        valueService.changeValue(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2020, 1, 1), null, null);
        valueService.changeValue(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("500.00"), LocalDate.of(2026, 6, 1), null, null);

        PayrollRunItem item = calculate(scenario);

        // March is inside the first period: 400. Not 500 (today's rate) and not
        // 420 (the employee column). This is the defect the whole table closes.
        assertThat(item.getHourlyRateSystem()).isEqualByComparingTo("400.00");
        assertThat(item.getHourlyRate()).isEqualByComparingTo("400.00");

        // 10560 minutes / 60 x 400
        assertThat(categoryRow(item, scenario.workCategory()).getAmount())
                .isEqualByComparingTo("70400.00");
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("76400.00");
    }

    @Test
    @DisplayName("14b. a later month gets the later rate")
    void aLaterMonthGetsTheLaterRate() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 7)).build();
        Long employeeId = scenario.employee().getId();

        valueService.changeValue(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2020, 1, 1), null, null);
        valueService.changeValue(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("500.00"), LocalDate.of(2026, 6, 1), null, null);

        PayrollRunItem item = calculate(scenario);

        assertThat(item.getHourlyRateSystem()).isEqualByComparingTo("500.00");
        assertThat(categoryRow(item, scenario.workCategory()).getAmount())
                .isEqualByComparingTo("88000.00");
    }

    @Test
    @DisplayName("14c. with no history the employee column is still used — old behaviour intact")
    void fallsBackToTheEmployeeColumn() {
        // 129 of 135 real employees have no history to backfill, so this path is
        // the normal one, not the exception.
        var scenario = fixture.scenario().build();

        PayrollRunItem item = calculate(scenario);

        assertThat(item.getHourlyRateSystem()).isEqualByComparingTo("420.00");
        assertThat(categoryRow(item, scenario.workCategory()).getAmount())
                .isEqualByComparingTo("73920.00");
    }

    @Test
    @DisplayName("14d. no rate anywhere leaves the item's rate alone — 'not configured' is not zero")
    void noRateAnywhereLeavesTheItemAlone() {
        var scenario = fixture.scenario().withoutEmployeeHourlyRate().build();

        PayrollRunItem item = calculate(scenario);

        // The item was initialised at 420 and neither source has an opinion, so
        // nothing overwrites it. Zeroing here would wipe the rate of every employee
        // the backfill could not reconstruct — 129 of 135 of them.
        assertThat(item.getHourlyRateSystem()).isEqualByComparingTo("420.00");
        assertThat(categoryRow(item, scenario.workCategory()).getAmount())
                .isEqualByComparingTo("73920.00");
    }

    @Test
    @DisplayName("14e. an overridden rate survives a history-driven recalculation")
    void anOverriddenRateSurvivesTheHistory() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 3)).build();
        Long employeeId = scenario.employee().getId();
        valueService.changeValue(employeeId, EmployeePayrollValueCodes.HOURLY_RATE,
                new BigDecimal("400.00"), LocalDate.of(2020, 1, 1), null, null);
        calculate(scenario);

        PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
        patch.setHourlyRate(new BigDecimal("600.00"));
        payrollRunItemService.patch(scenario.item().getId(), patch);

        PayrollRunItem stale = payrollRunItemService.findById(scenario.item().getId());
        stale.setNeedsRecalculation(true);
        entityManager.flush();

        PayrollRunItem item = calculate(scenario);

        assertThat(item.getHourlyRate())
                .as("a human decision about this month outranks the rate history")
                .isEqualByComparingTo("600.00");
        assertThat(item.getHourlyRateSystem())
                .as("the system rate still tracks the history, so the override stays visible")
                .isEqualByComparingTo("400.00");
    }

    // ═══ 15. Transport actually computes now (phase 3) ══════════════════════

    @Test
    @DisplayName("15. PER-DAY mode: worked days x the company rate")
    void transportIsPaidPerWorkedDay() {
        var scenario = fixture.scenario().build();

        // Three shift records, one of them with no work in it.
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 6, 480, 450);
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 4), 6, 480, 470);
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 5), 6, 480, 0);

        PayrollRunItem item = calculate(scenario);

        // 2 worked days x 350 (the fixture's company rate). The zero-work shift is
        // not a day worked. This is the money risk R1 is about: the line was
        // structurally 0 before the calculator existed.
        assertThat(transportLine(item).getSystemQuantity()).isEqualByComparingTo("2");
        assertThat(transportLine(item).getSystemUnitAmount()).isEqualByComparingTo("350.00");
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("700.00");
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("80620.00");

        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE")
                .orElseThrow().getCalculationInputs())
                .containsEntry("mode", "PER_WORKED_DAY")
                .containsEntry("workedDays", 2);
    }

    @Test
    @DisplayName("15b. FIXED mode: the whole monthly amount, whatever was worked")
    void aFixedMonthlyAmountIgnoresAttendance() {
        var scenario = fixture.scenario().build();
        valueService.changeValue(scenario.employee().getId(),
                EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("8000.00"), LocalDate.of(2020, 1, 1), null, null);

        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 6, 480, 450);
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 4), 6, 480, 470);

        PayrollRunItem item = calculate(scenario);

        // 8000, not 2 x anything. A fixed employee is not paid more for coming in
        // more often, so no day is counted at all — quantity 1, because showing it
        // as "2 x 4000" on a payslip would be a fiction.
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("8000.00");
        assertThat(transportLine(item).getSystemQuantity()).isEqualByComparingTo("1");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE")
                .orElseThrow().getCalculationInputs())
                .containsEntry("mode", "FIXED_MONTHLY");
    }

    @Test
    @DisplayName("15b2. a fixed employee who worked nothing is still paid the full amount")
    void aFixedAmountIsPaidWithNoShiftsAtAll() {
        var scenario = fixture.scenario().build();
        valueService.changeValue(scenario.employee().getId(),
                EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("8000.00"), LocalDate.of(2020, 1, 1), null, null);

        PayrollRunItem item = calculate(scenario);

        // The clearest statement of the difference between the two modes: with no
        // shifts the per-day mode pays nothing and the fixed mode pays everything.
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("15c. a fixed amount that starts later leaves earlier months on the per-day mode")
    void aFixedAmountStartingLaterLeavesEarlierMonthsPerDay() {
        var march = fixture.scenario().period(YearMonth.of(2026, 3)).build();

        // What the backfill produces: the fixed amount starts from the first month
        // not yet calculated.
        valueService.changeValue(march.employee().getId(),
                EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("8000.00"), LocalDate.of(2026, 8, 1), "backfill", null);

        fixture.dailyReport(march.employee(), LocalDate.of(2026, 3, 3), 6, 480, 450);

        PayrollRunItem item = calculate(march);

        // March is before the fixed amount begins, so it falls to the per-day mode
        // and is paid 1 x 350 — NOT nothing.
        //
        // ⚠️ OPEN-15: the per-day mode reads no per-employee value, so no start date
        // can hold it back. Every historical month in which somebody worked now
        // gains transport the next time it is opened. Locking closed months is the
        // only control for that.
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("350.00");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE")
                .orElseThrow().getCalculationInputs())
                .containsEntry("mode", "PER_WORKED_DAY");
    }

    @Test
    @DisplayName("15c2. with no company rate and no fixed amount, the line is an explained zero")
    void neitherModeIsAnExplainedZero() {
        var scenario = fixture.scenario().transportRate("0").build();
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 6, 480, 450);

        PayrollRunItem item = calculate(scenario);

        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("15d. the foreign scheme excludes transport even with a rate and shifts")
    void anExcludedSchemeBeatsTheRate() {
        var scenario = fixture.scenario()
                .scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT)
                .foreigner(true)
                .denyAdjustment("MEAL_ALLOWANCE", "TRANSPORT_ALLOWANCE")
                .build();

        valueService.changeValue(scenario.employee().getId(),
                EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY,
                new BigDecimal("8000.00"), LocalDate.of(2020, 1, 1), null, null);
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 6, 480, 450);

        PayrollRunItem item = calculate(scenario);

        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("0.00");
        // A rate IS configured; the scheme is what refuses it, so no line exists.
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE")).isEmpty();
    }

    @Test
    @DisplayName("15e. every calculation_key in the catalogue has a calculator")
    void everyCalculationKeyHasACalculator() {
        fixture.catalogue();

        // D6: an unknown key must stop the run, not pay a silent zero. That is only
        // safe if the catalogue and the registry agree, so the agreement is checked
        // here rather than discovered on payroll day.
        List<String> missing = adjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull().stream()
                .map(c -> c.getCalculationKey())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .filter(key -> !calculatorRegistry.knows(key))
                .toList();

        assertThat(missing)
                .as("calculation_key values with no calculator; registered: %s", calculatorRegistry.knownKeys())
                .isEmpty();
    }

    @Test
    @DisplayName("15f. an unknown calculation_key throws instead of paying zero")
    void unknownKeyThrows() {
        assertThatThrownBy(() -> calculatorRegistry.require("MY_NEW_MAGIC_FORMULA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MY_NEW_MAGIC_FORMULA")
                .hasMessageContaining("MANUAL");
    }

    // ═══ 16. One source of truth (phase 4) ══════════════════════════════════

    @Test
    @DisplayName("16. the legacy item columns still mirror the adjustment rows exactly")
    void legacyColumnsStillMirrorTheAdjustments() {
        var scenario = fixture.scenario().build();
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 6, 480, 450);

        PayrollRunItem item = calculate(scenario);

        // This assertion IS the phase 4a dual-write check, run in a test instead of
        // over a production month. The columns are no longer read for any total, but
        // they must stay truthful until phase 7 drops them — a mirror that has
        // drifted is worse than no mirror, because somebody will read it.
        assertThat(lineAmount(item, "MEAL_ALLOWANCE"))
                .isEqualByComparingTo(mealAdjustmentAmount(item.getId()));
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE"))
                .isEqualByComparingTo(adjustmentRepository
                        .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE")
                        .orElseThrow().getAmount());
    }

    @Test
    @DisplayName("16b. meal is counted once, not twice, now that the row is the source")
    void mealIsCountedExactlyOnce() {
        var scenario = fixture.scenario().build();

        PayrollRunItem item = calculate(scenario);

        // 73920 work + 6000 meal. If the old direct-add had survived alongside the
        // new GROSS_PLUS sum this would be 85920, and if the meal row had been
        // dropped from the sum it would be 73920. Both failure modes are one number
        // away and neither is visible without this assertion.
        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo("79920.00");
    }

    @Test
    @DisplayName("16c. a second row of the same category is refused by the service")
    void aSecondRowOfTheSameCategoryIsRefused() {
        var scenario = fixture.scenario().build();

        PayrollAdjustmentCreateRequest request = new PayrollAdjustmentCreateRequest();
        request.setPayrollRunItemId(scenario.item().getId());
        request.setPayrollAdjustmentCategoryId(
                adjustmentCategoryRepository.findByCode("OTHER").orElseThrow().getId());
        request.setAmount(new BigDecimal("100.00"));

        // A category is a labelled slot on the payslip, not a ledger of entries.
        // The unique constraint is the real guarantee; this is the message somebody
        // can act on.
        assertThatThrownBy(() -> payrollAdjustmentService.create(request))
                .isInstanceOf(ConflictException.class);
    }

    // ═══ Behaviour the plan requires but that does not exist yet ════════════

    // ═══ 12. Period-correct pricing (phase 1) ═══════════════════════════════

    @Test
    @DisplayName("12. recalculating an old month uses the price in force THEN, not today's")
    void periodCorrectPrice() {
        // March 2026, priced at 300 — the rate the fixture puts in force from a
        // year before any scenario.
        var scenario = fixture.scenario().period(YearMonth.of(2026, 3)).build();

        // The rate then rose to 500, from June. That is AFTER March but BEFORE
        // today, so it is the value now() would find and the value the period
        // must not find. Without this second row both readings agree and the test
        // would pass whatever the code does.
        fixture.appSetting("meal_allowance_per_day", new BigDecimal("500.00"),
                OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC), null);
        fixture.appSetting("transport_allowance_per_day", new BigDecimal("900.00"),
                OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC), null);

        PayrollRunItem item = calculate(scenario);

        assertThat(lineSystemUnit(item, "MEAL_ALLOWANCE"))
                .as("March is priced at March's rate, not at the rate in force today")
                .isEqualByComparingTo("300.00");
        assertThat(lineAmount(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("6000.00");

        // 0 since phase 3: transport is priced from the employee's own rate, and
        // this employee has none. The global transport_allowance_per_day setting is
        // no longer read by anything.
        assertThat(transportLine(item).getSystemAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("12b. a month AFTER the rise is priced at the new rate")
    void aLaterMonthGetsTheNewPrice() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 7)).build();

        fixture.appSetting("meal_allowance_per_day", new BigDecimal("500.00"),
                OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC), null);

        PayrollRunItem item = calculate(scenario);

        // The mirror of 12: pinning "old months keep the old rate" is only half the
        // rule, and a fix that simply froze every price would also satisfy it.
        assertThat(lineSystemUnit(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("500.00");
        assertThat(lineAmount(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("12c. a rate that starts mid-month does not reprice that month")
    void aMidMonthRiseAppliesFromTheNextMonth() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 7)).build();

        // In force from 15 July: not yet true on 1 July, so July keeps 300.
        fixture.appSetting("meal_allowance_per_day", new BigDecimal("500.00"),
                OffsetDateTime.of(2026, 7, 15, 0, 0, 0, 0, ZoneOffset.UTC), null);

        PayrollRunItem item = calculate(scenario);

        assertThat(lineSystemUnit(item, "MEAL_ALLOWANCE"))
                .as("a payroll month is priced by what was true when it started")
                .isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("12d. an overridden unit price is not repriced by the period rule")
    void overrideBeatsThePeriodRate() {
        var scenario = fixture.scenario().period(YearMonth.of(2026, 3)).build();
        calculate(scenario);

        patchLine(scenario.item().getId(), "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("420.00")));

        fixture.appSetting("meal_allowance_per_day", new BigDecimal("500.00"),
                OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC), null);

        PayrollRunItem stale = payrollRunItemService.findById(scenario.item().getId());
        stale.setNeedsRecalculation(true);
        entityManager.flush();

        PayrollRunItem item = calculate(scenario);

        assertThat(lineUnit(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("420.00");
        assertThat(lineSystemUnit(item, "MEAL_ALLOWANCE"))
                .as("the system rate still tracks the period, so the override stays visible as a difference")
                .isEqualByComparingTo("300.00");
    }

    // ═══ 17. Bonus and locking ══════════════════════════════════════════════

    @Test
    @DisplayName("17. the bonus is the employee's base plus the hours tier")
    void bonusIsBasePlusTier() {
        var scenario = fixture.scenario().build();          // 10 560 minutes = 176 h
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);  // 176 >= 160, base earned
        fixture.bonusTier(YearMonth.of(2026, 9), 100, "1000.00");
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");
        fixture.bonusTier(YearMonth.of(2026, 9), 200, "4000.00");

        PayrollRunItem item = calculate(scenario);

        // 5000 base + 2500 for the highest tier reached. Not 4000: 176 hours does
        // not reach 200. Not 3500: the tiers do not accumulate.
        assertThat(bonusTotal(item)).isEqualByComparingTo("7500.00");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS")
                .orElseThrow().getAmount()).isEqualByComparingTo("7500.00");
    }

    @Test
    @DisplayName("17g. base and correction stay separate on the line, not folded together")
    void baseAndCorrectionAreSeparate() {
        var scenario = fixture.scenario().build();          // 176 h
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");

        PayrollRunItem item = calculate(scenario);

        // The base used to hold the WHOLE 7 500 and the correction sat at zero, so
        // the panel said "Osnovni bonus 7.500" for an employee whose category is
        // 5.000. The total was right; its two parts were not.
        assertThat(bonusBase(item)).isEqualByComparingTo("5000.00");
        assertThat(bonusSystemBase(item)).isEqualByComparingTo("5000.00");
        assertThat(bonusAdditional(item)).isEqualByComparingTo("2500.00");
        assertThat(bonusSystemAdditional(item)).isEqualByComparingTo("2500.00");
        assertThat(bonusTotal(item)).isEqualByComparingTo("7500.00");
    }

    @Test
    @DisplayName("17h. overriding the base leaves the tier alone, and the total follows both")
    void overridingTheBaseKeepsTheTier() {
        var scenario = fixture.scenario().build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");
        calculate(scenario);

        patchBonusBase(scenario.item().getId(), "6000.00");

        PayrollRunItem item = calculate(scenario);

        // The override survives the recalculation; the tier is still the rules'.
        assertThat(bonusBase(item)).isEqualByComparingTo("6000.00");
        assertThat(bonusAdditional(item)).isEqualByComparingTo("2500.00");
        assertThat(bonusTotal(item)).isEqualByComparingTo("8500.00");
        // The rules' own figure stays readable beside it — that is what the panel
        // strikes through — and the line is NOT marked as a typed total, because
        // the formula still ran for the tier.
        assertThat(bonusSystemBase(item)).isEqualByComparingTo("5000.00");
        assertThat(adjustmentRepository.findByItemIdAndCategoryCode(item.getId(), CAT_BONUS)
                .orElseThrow().getIsOverridden()).isFalse();
    }

    @Test
    @DisplayName("17i. a legacy override with no reason does not block the recalculation")
    void aLegacyOverrideDoesNotBlockRecalculation() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        // The state chk_pa_override_reason was deliberately made NOT VALID to
        // tolerate: flagged as overridden, no reason, because the rule did not
        // exist when it was recorded. 24 such rows existed across 12 items.
        PayrollAdjustment legacy = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MEAL_ALLOWANCE").orElseThrow();
        // The constraint refuses to CREATE this state, which is the point of it.
        // Reproducing history means doing what history did: the rows existed
        // first, and the rule was added afterwards as NOT VALID. DDL inside the
        // test transaction rolls back with it.
        entityManager.createNativeQuery(
                "ALTER TABLE payroll_adjustments DROP CONSTRAINT chk_pa_override_reason")
                .executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE payroll_adjustments SET is_overridden = TRUE, override_reason = NULL WHERE id = :id")
                .setParameter("id", legacy.getId())
                .executeUpdate();
        entityManager.createNativeQuery("""
                ALTER TABLE payroll_adjustments ADD CONSTRAINT chk_pa_override_reason
                CHECK (is_overridden = FALSE
                       OR (override_reason IS NOT NULL AND length(trim(override_reason)) > 0))
                NOT VALID""")
                .executeUpdate();
        entityManager.clear();

        // NOT VALID exempts a row from the INITIAL check, never from being checked
        // when something updates it. Before the fix this threw
        // DataIntegrityViolationException and the whole item failed to recalculate.
        PayrollRunItem item = calculate(scenario);

        assertThat(item).isNotNull();
        PayrollAdjustment after = adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MEAL_ALLOWANCE").orElseThrow();
        // The calculation wrote its own figure, so the row is no longer anybody's
        // typed-in total — and says so.
        assertThat(after.getIsOverridden()).isFalse();
        assertThat(after.getAmount()).isEqualByComparingTo(after.getSystemAmount());
    }

    @Test
    @DisplayName("17j. an override WITH a reason is left exactly as it is")
    void aGenuineOverrideSurvives() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        PayrollAdjustment line = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "TRANSPORT_ALLOWANCE").orElseThrow();
        entityManager.createNativeQuery(
                "UPDATE payroll_adjustments SET is_overridden = TRUE, override_reason = :r WHERE id = :id")
                .setParameter("r", "Dogovoreno sa direktorom")
                .setParameter("id", line.getId())
                .executeUpdate();
        entityManager.clear();

        calculate(scenario);

        // Only rows the old rule allowed to exist are cleared. A reason is what
        // separates a decision somebody made from a flag nobody can explain.
        PayrollAdjustment after = adjustmentRepository.findById(line.getId()).orElseThrow();
        assertThat(after.getOverrideReason()).isEqualTo("Dogovoreno sa direktorom");
    }

    @Test
    @DisplayName("17k. a line records what the rules produced for its correction, beside what applies")
    void theLineCarriesItsOwnSystemCorrection() {
        var scenario = fixture.scenario().build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");

        PayrollRunItem item = calculate(scenario);

        // system_correction_amount is the column the line was missing: every other
        // figure a person can change had a system counterpart beside it, and this
        // one did not — so a tier the rules paid and a tier somebody typed were the
        // same row. Nothing reads it yet (step 1); it exists so step 2 can.
        PayrollAdjustment bonus = adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS").orElseThrow();
        assertThat(bonus.getSystemCorrectionAmount()).isNotNull();

        // Step 1 changes no arithmetic: the money is still what it was.
        assertThat(bonusTotal(item)).isEqualByComparingTo("7500.00");
        assertThat(bonusBase(item)).isEqualByComparingTo("5000.00");
        assertThat(bonusAdditional(item)).isEqualByComparingTo("2500.00");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 2 — the LINE holds the override state
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Two tests here proved the meal and transport ITEM COLUMNS were no longer the
    // source, by wiping them and watching the line put the figure back. Those
    // columns were dropped by 2026-09-04-01, so there is nothing left to wipe and
    // nothing left to prove — the line is the only place the figure exists.

    @Test
    @DisplayName("18a. a repriced meal survives a recalculation because the LINE remembers it")
    void theLineRemembersTheMealPrice() {
        var scenario = fixture.scenario().mealRate("300.00").mealCount(20).build();
        patchLine(scenario.item().getId(), "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("350.00")));

        // The price is on the row, which is where the recalculation now looks.
        PayrollAdjustment line = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MEAL_ALLOWANCE").orElseThrow();
        assertThat(line.getUnitAmount()).isEqualByComparingTo("350.00");
        assertThat(line.getHasManualInput()).isTrue();

        PayrollRunItem item = calculate(scenario);

        assertThat(lineUnit(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("350.00");
        assertThat(lineSystemUnit(item, "MEAL_ALLOWANCE")).isEqualByComparingTo("300.00");
    }



    @Test
    @DisplayName("18d. the bonus line carries its parts, and the total still adds up")
    void theBonusLineCarriesItsParts() {
        var scenario = fixture.scenario().build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");
        calculate(scenario);

        PayrollAdjustment line = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MONTHLY_BONUS").orElseThrow();

        // amount stays the effective TOTAL — the figure the earnings sum reads.
        // The base is recoverable as amount minus correction_amount, which is why
        // that sum did not have to change and cannot double-count the tier.
        assertThat(line.getAmount()).isEqualByComparingTo("7500.00");
        assertThat(line.getCorrectionAmount()).isEqualByComparingTo("2500.00");
        assertThat(line.getSystemCorrectionAmount()).isEqualByComparingTo("2500.00");
        assertThat(line.getAmount().subtract(line.getCorrectionAmount()))
                .isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("18e. both parts are edited independently and both survive a recalculation")
    void theLineCarriesBothEditedParts() {
        var scenario = fixture.scenario().build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");
        calculate(scenario);

        // THE SHAPE THIS GUARDS. The two parts share one line — amount is the
        // total, correction_amount the tier — so an edit to either has to leave
        // the other where it was. Sending the base as a typed `amount` was the
        // obvious route and the wrong one: a typed total has no parts, so the
        // next recalculation collapsed the split into "base = everything,
        // additional = 0". baseAmount exists so the split survives.
        patchBonusBase(scenario.item().getId(), "6000.00");
        patchBonusAdditional(scenario.item().getId(), "1000.00");

        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        PayrollRunItem item = calculate(scenario);

        assertThat(bonusBase(item)).isEqualByComparingTo("6000.00");
        assertThat(bonusAdditional(item)).isEqualByComparingTo("1000.00");
        assertThat(bonusTotal(item)).isEqualByComparingTo("7000.00");
        // The rules' own figures are still there, so the panel can show what
        // would otherwise have been paid.
        assertThat(bonusSystemBase(item)).isEqualByComparingTo("5000.00");
        assertThat(bonusSystemAdditional(item)).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("18e2. editing the tier moves the total, it does not eat the base")
    void editingTheTierKeepsTheBase() {
        var scenario = fixture.scenario().build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");
        calculate(scenario);

        // The regression: correction_amount was set on its own and amount left
        // alone, so raising the tier by 500 silently took 500 off the base and
        // the employee was paid exactly what they were before.
        patchBonusAdditional(scenario.item().getId(), "3000.00");

        PayrollRunItem item = calculate(scenario);

        assertThat(bonusBase(item)).isEqualByComparingTo("5000.00");
        assertThat(bonusAdditional(item)).isEqualByComparingTo("3000.00");
        assertThat(bonusTotal(item)).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("18e3. re-sending the rules' figure is a reset, and leaves the other part alone")
    void resendingTheSystemFigureResetsOnePart() {
        var scenario = fixture.scenario().build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");
        calculate(scenario);

        patchBonusBase(scenario.item().getId(), "6000.00");
        patchBonusAdditional(scenario.item().getId(), "1000.00");

        // WHY THE PANEL DOES NOT SEND clearOverride. Both parts live on one line,
        // so clearing it would throw the other away — undoing the base edit would
        // silently drop the correction somebody typed. Re-sending the rules'
        // figure is a real reset, because the recalculation keeps a part only
        // while it differs from the system's.
        patchBonusBase(scenario.item().getId(), "5000.00");

        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        PayrollRunItem item = calculate(scenario);

        assertThat(bonusBase(item)).isEqualByComparingTo("5000.00");
        assertThat(bonusAdditional(item))
                .as("the edited tier is untouched by a reset of the base")
                .isEqualByComparingTo("1000.00");
        assertThat(bonusTotal(item)).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("18f. an excluded bonus leaves nothing behind in the item columns")
    void anExcludedBonusZeroesTheColumnsToo() {
        // Found by the step-3 diagnostic on real data: the columns claimed a 4.000
        // bonus for an employee whose scheme pays none. The money was right — the
        // sums filter on is_applied — but the parameters panel reads the columns.
        var scenario = fixture.scenario().scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).build();
        fixture.bonusCategory(scenario.employee(), "4000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 100);

        // Leave a figure on the line, as a run initialised before the scheme change
        // would have. neutraliseExcludedAdjustments zeroes it at the END of the
        // recalculation — long after the bonus block used to read it.
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustments SET amount = 4000.00, has_manual_input = TRUE
                WHERE payroll_run_item_id = :id
                  AND payroll_adjustment_category_id =
                      (SELECT id FROM payroll_adjustment_categories WHERE code = 'MONTHLY_BONUS')""")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        PayrollRunItem item = calculate(scenario);

        assertThat(bonusTotal(item)).isEqualByComparingTo("0.00");
        assertThat(bonusBase(item)).isEqualByComparingTo("0.00");
        assertThat(bonusAdditional(item)).isEqualByComparingTo("0.00");

        PayrollAdjustment line = adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS").orElseThrow();
        assertThat(line.getIsApplied()).isFalse();
        assertThat(line.getAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("18g. a typed total leaves the parts saying something true")
    void aTypedTotalReconcilesItsParts() {
        var scenario = fixture.scenario().build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);
        fixture.bonusTier(YearMonth.of(2026, 9), 170, "2500.00");
        calculate(scenario);

        // Through the real API, not native SQL: applyAdjustmentPatch is what sets
        // is_overridden together with its reason, and going round it was testing a
        // state the application never produces.
        AdjustmentPatchDto typed = new AdjustmentPatchDto();
        typed.setId(adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MONTHLY_BONUS").orElseThrow().getId());
        typed.setAmount(new BigDecimal("2000.00"));
        typed.setOverrideReason("Dogovoreno sa direktorom");
        PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
        patch.setAdjustments(List.of(typed));
        payrollRunItemService.patch(scenario.item().getId(), patch);

        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        PayrollRunItem item = calculate(scenario);

        // The parts used to keep whatever split preceded the override — one real
        // item read "base 0 + additional 2.000" beside a line holding 2.000 as base.
        assertThat(bonusTotal(item)).isEqualByComparingTo("2000.00");
        assertThat(bonusBase(item)).isEqualByComparingTo("2000.00");
        assertThat(bonusAdditional(item)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("18h. keeping a person's figure still records what the calculation produced")
    void aHumanAmountDoesNotFreezeTheSystemFigures() {
        var scenario = fixture.scenario().employeeTransportRate("350.00").build();
        patchLine(scenario.item().getId(), "TRANSPORT_ALLOWANCE", d -> {
            d.setAmount(new BigDecimal("9999.00"));
            d.setOverrideReason("Dogovoreno");
        });

        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        PayrollRunItem item = calculate(scenario);
        PayrollAdjustment line = adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "TRANSPORT_ALLOWANCE").orElseThrow();

        // The person's figure is kept...
        assertThat(line.getAmount()).isEqualByComparingTo("9999.00");
        assertThat(lineAmount(item, "TRANSPORT_ALLOWANCE")).isEqualByComparingTo("9999.00");

        // ...and the calculation still says what it would have paid. Skipping the
        // whole sync froze these, so the line could not answer "what would the
        // rules give?" — and read as never calculated in the step-3 report even
        // after a sweep had visited it.
        assertThat(line.getCalculatedAt()).isNotNull();
        assertThat(line.getSystemAmount()).isNotNull();
        assertThat(line.getSystemAmount()).isNotEqualByComparingTo("9999.00");
    }

    @Test
    @DisplayName("18i. a category the scheme excludes cannot be edited into existence")
    void anExcludedCategoryRefusesAnEdit() {
        // Found by verifying a real month: a bonus was entered for an employee on
        // FOREIGN_FIXED_COEFFICIENT, whose rule for MONTHLY_BONUS is is_allowed =
        // false. The patch re-applied the line and 8.000 entered total_net_earnings.
        var scenario = fixture.scenario().build();
        // The production shape: the LINE exists — a run initialised before the rule
        // changed — and the scheme now refuses the category. fixture.denyAdjustment
        // never creates the line at all, so it cannot reproduce this.
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_category_scheme_rules r
                SET is_allowed = FALSE
                FROM payroll_adjustment_categories c, employee_compensation_scheme_history h
                WHERE c.id = r.payroll_adjustment_category_id
                  AND c.code = 'MONTHLY_BONUS'
                  AND h.employee_id = :emp AND h.archived_at IS NULL
                  AND r.compensation_scheme_id = h.compensation_scheme_id""")
                .setParameter("emp", scenario.employee().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        AdjustmentPatchDto edit = new AdjustmentPatchDto();
        edit.setId(adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MONTHLY_BONUS").orElseThrow().getId());
        edit.setAmount(new BigDecimal("8000.00"));
        edit.setOverrideReason("Dogovoreno");
        PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
        patch.setAdjustments(List.of(edit));

        assertThatThrownBy(() -> payrollRunItemService.patch(scenario.item().getId(), patch))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("MONTHLY_BONUS");
    }

    @Test
    @DisplayName("18j. and the money never reaches the total")
    void anExcludedCategoryNeverReachesTheTotal() {
        var scenario = fixture.scenario().build();
        // The production shape: the LINE exists — a run initialised before the rule
        // changed — and the scheme now refuses the category. fixture.denyAdjustment
        // never creates the line at all, so it cannot reproduce this.
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_category_scheme_rules r
                SET is_allowed = FALSE
                FROM payroll_adjustment_categories c, employee_compensation_scheme_history h
                WHERE c.id = r.payroll_adjustment_category_id
                  AND c.code = 'MONTHLY_BONUS'
                  AND h.employee_id = :emp AND h.archived_at IS NULL
                  AND r.compensation_scheme_id = h.compensation_scheme_id""")
                .setParameter("emp", scenario.employee().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // Straight onto the line, as the state that already existed in the database.
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustments SET amount = 8000.00, is_applied = TRUE
                WHERE payroll_run_item_id = :id
                  AND payroll_adjustment_category_id =
                      (SELECT id FROM payroll_adjustment_categories WHERE code = 'MONTHLY_BONUS')""")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", scenario.item().getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        PayrollRunItem item = calculate(scenario);

        // neutraliseExcludedAdjustments takes it back out, and the totals never
        // counted it. The defect was the window in between, not the arithmetic.
        PayrollAdjustment line = adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS").orElseThrow();
        assertThat(line.getIsApplied()).isFalse();
        assertThat(bonusTotal(item)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("18k. every way of editing the bonus is refused, not just the first one found")
    void everyBonusEditRouteIsRefused() {
        // Reported twice. The first fix covered applyAdjustmentPatch, which handles
        // the `adjustments` array; the panel sent baseBonusAmount as an ITEM field
        // and went through a different branch entirely. Same defect, other door.
        //
        // There is one door now — the item fields are gone — but the bonus has
        // three ways through it, and each is asserted. A part is not a smaller
        // kind of edit that can slip past the check on totals.
        var scenario = fixture.scenario().build();
        denyBonusForThisEmployee(scenario);

        assertThatThrownBy(() -> patchBonusBase(scenario.item().getId(), "3000.00"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(CAT_BONUS);

        assertThatThrownBy(() -> patchBonusAdditional(scenario.item().getId(), "5000.00"))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> patchLine(scenario.item().getId(), CAT_BONUS, d -> {
            d.setAmount(new BigDecimal("8000.00"));
            d.setOverrideReason("Dogovoreno");
        })).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("18l. nothing reached the money on any of those attempts")
    void noneOfThoseAttemptsMovedThePay() {
        var scenario = fixture.scenario().build();
        denyBonusForThisEmployee(scenario);
        PayrollRunItem before = calculate(scenario);
        BigDecimal earningsBefore = before.getTotalNetEarnings();

        assertThatThrownBy(() -> patchBonusBase(scenario.item().getId(), "3000.00"))
                .isInstanceOf(ConflictException.class);

        // The real complaint was not the missing message — it was that the figure
        // was counted. 2.660 became 5.660 on a refused category.
        PayrollRunItem after = payrollRunItemService.findById(scenario.item().getId());
        assertThat(after.getTotalNetEarnings()).isEqualByComparingTo(earningsBefore);
        assertThat(bonusTotal(after)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("17b. below the minimum hours the base is nothing, not a proportion")
    void belowMinimumHoursThereIsNoBase() {
        var scenario = fixture.scenario().build();          // 176 h
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 180);  // one shift short
        fixture.bonusTier(YearMonth.of(2026, 9), 100, "1000.00");

        PayrollRunItem item = calculate(scenario);

        // The tier is still earned — it has its own threshold. Only the base is
        // withheld, and withheld entirely: a threshold, not a pro rata.
        assertThat(bonusTotal(item)).isEqualByComparingTo("1000.00");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS")
                .orElseThrow().getCalculationInputs())
                .containsEntry("baseBonusReason", "BELOW_MINIMUM_HOURS");
    }

    @Test
    @DisplayName("17e. a manual time correction decides the bonus, because it decides the hours")
    void aTimeCorrectionMovesTheBonusThreshold() {
        var scenario = fixture.scenario().build();          // 176 h worked
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 180);  // one shift short

        // Without the correction this is 17b: no base.
        // The administrator books the forgotten shift as a time correction.
        PayrollRunItemPatchRequest patch = objectMapper.convertValue(java.util.Map.of(
                "manualAdjustedMinutes", 480,
                "manualAdjustedMinutesReason", "Zaboravljena smena 30.09."),
                PayrollRunItemPatchRequest.class);
        payrollRunItemService.patch(scenario.item().getId(), patch);

        PayrollRunItem item = calculate(scenario);

        // 176 + 8 = 184 h, over the 180 threshold. The bonus used to read
        // total_work_minutes and ignore the correction, so the hours moved on
        // screen and the bonus did not.
        assertThat(item.getTotalPayrollMinutes()).isEqualTo(10_560 + 480);
        assertThat(bonusBase(item)).isEqualByComparingTo("5000.00");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS")
                .orElseThrow().getCalculationInputs())
                .containsEntry("hoursSource", "total_payroll_minutes");
    }

    @Test
    @DisplayName("17f. a correction that takes hours away takes the bonus with it")
    void aNegativeCorrectionRemovesTheBonus() {
        var scenario = fixture.scenario().build();          // 176 h
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 170);  // comfortably earned

        PayrollRunItemPatchRequest patch = objectMapper.convertValue(java.util.Map.of(
                "manualAdjustedMinutes", -480,
                "manualAdjustedMinutesReason", "Smena evidentirana dvaput"),
                PayrollRunItemPatchRequest.class);
        payrollRunItemService.patch(scenario.item().getId(), patch);

        PayrollRunItem item = calculate(scenario);

        // 176 - 8 = 168 h, under 170. The threshold has to cut both ways or the
        // correction would only ever be able to add money.
        assertThat(bonusBase(item)).isEqualByComparingTo("0.00");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS")
                .orElseThrow().getCalculationInputs())
                .containsEntry("baseBonusReason", "BELOW_MINIMUM_HOURS");
    }

    @Test
    @DisplayName("17c. a scheme that pays no hourly bonus zeroes the line, rules or not")
    void aSchemeWithoutBonusPaysNothing() {
        fixture.ensureScheme("IT-COMM-2", "Komercijala", true, false);
        var scenario = fixture.scenario().scheme("IT-COMM-2").build();
        fixture.bonusCategory(scenario.employee(), "5000.00");
        fixture.bonusMinHours(YearMonth.of(2026, 9), 160);

        PayrollRunItem item = calculate(scenario);

        assertThat(bonusTotal(item)).isEqualByComparingTo("0.00");
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "MONTHLY_BONUS")
                .orElseThrow().getCalculationInputs())
                .containsEntry("reason", "SCHEME_PAYS_NO_BONUS");
    }

    @Test
    @DisplayName("17d. PAID_PREVIOUS_PERIOD shows the settlements total and is not counted twice")
    void paidPreviousPeriodShowsTheSettlementsTotal() {
        var scenario = fixture.scenario().build();
        fixture.adjustmentAmount(scenario, "INSTALLMENT", "2000.00");
        fixture.adjustmentAmount(scenario, "PHONE_PREVIOUS_MONTH", "700.00");
        fixture.adjustmentAmount(scenario, "PAID_PART_1", "30000.00");
        fixture.adjustmentAmount(scenario, "PAID_PART_2", "20000.00");

        PayrollRunItem item = calculate(scenario);

        BigDecimal settlements = new BigDecimal("52700.00");
        assertThat(item.getPreviouslyPaidAmount()).isEqualByComparingTo(settlements);

        // The line SHOWS that sum — the four settlements added up.
        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "PAID_PREVIOUS_PERIOD")
                .orElseThrow().getAmount()).isEqualByComparingTo(settlements);

        // And is NOT among them. If it were, everything would be deducted twice and
        // the balance would be 52 700 short. This is why the settlements side stayed
        // on section_code while the earnings side moved to impact codes.
        assertThat(item.getCurrentBalanceAmount())
                .isEqualByComparingTo(item.getTotalNetEarnings().subtract(settlements));
    }

    @Test
    @DisplayName("17d2. the phone is edited on its line, and still reaches no total")
    void phoneCurrentMonthIsEditedOnItsLine() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        // Through the adjustments array: PHONE_CURRENT_MONTH is a MANUAL category
        // whose editable input IS the amount. current_month_telephone used to be
        // the authoritative store with the line kept in step beside it, and the
        // line sat at zero for every phone entered before the component backfill.
        patchLine(scenario.item().getId(), "PHONE_CURRENT_MONTH",
                d -> d.setAmount(new BigDecimal("1200.00")));

        PayrollRunItem item = payrollRunItemService.findById(scenario.item().getId());

        assertThat(adjustmentRepository
                .findByItemIdAndCategoryCode(item.getId(), "PHONE_CURRENT_MONTH")
                .orElseThrow().getAmount())
                .isEqualByComparingTo("1200.00");

        // And it STILL reaches no balance. This month's phone is deducted NEXT
        // month, as PHONE_PREVIOUS_MONTH — section PHONE is what keeps it out of
        // previouslyPaid, and showing it must not start charging for it twice.
        assertThat(item.getPreviouslyPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(item.getCurrentBalanceAmount())
                .isEqualByComparingTo(item.getTotalNetEarnings());
    }

    @Test
    @DisplayName("17e. locking freezes the item and stops recalculation")
    void lockingFreezesTheItem() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        PayrollRunItem locked = payrollRunItemService.lock(scenario.item().getId());

        assertThat(locked.getStatus()).isEqualTo("LOCKED");
        assertThat(locked.getLockedAt()).isNotNull();

        // The whole point: what was calculated is now a record. A later change to
        // the rules cannot move it, because nothing recalculates it again.
        BigDecimal frozen = locked.getTotalNetEarnings();
        locked.setNeedsRecalculation(true);
        entityManager.flush();

        assertThat(calculate(scenario).getTotalNetEarnings()).isEqualByComparingTo(frozen);
    }

    @Test
    @DisplayName("17f. a required manual line with no input blocks locking")
    void requiredManualInputBlocksLocking() {
        var scenario = fixture.scenario().build();
        calculate(scenario);
        fixture.requireManualInput("OTHER");

        // "Not entered" is not "entered as zero". Freezing a month while somebody
        // still owes it a number would make a zero permanent that nobody decided on.
        assertThatThrownBy(() -> payrollRunItemService.lock(scenario.item().getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("OTHER");
    }

    @Test
    @DisplayName("17g. an explicit zero counts as input and unblocks locking")
    void anExplicitZeroUnblocksLocking() {
        var scenario = fixture.scenario().build();
        calculate(scenario);
        fixture.requireManualInput("OTHER");

        PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
        AdjustmentPatchDto entry = new AdjustmentPatchDto();
        entry.setId(scenario.adjustment("OTHER").getId());
        entry.setAmount(BigDecimal.ZERO);
        patch.setAdjustments(List.of(entry));
        payrollRunItemService.patch(scenario.item().getId(), patch);

        // Zero IS an answer once somebody has given it. has_manual_input is the only
        // thing that can tell the two apart.
        assertThat(payrollRunItemService.lock(scenario.item().getId()).getStatus())
                .isEqualTo("LOCKED");
    }

    // ═══ 18. The edit policy is enforced by the SERVER (phase 6) ════════════

    private PayrollRunItemPatchRequest patchOf(AdjustmentPatchDto entry) {
        PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
        patch.setAdjustments(List.of(entry));
        return patch;
    }

    private AdjustmentPatchDto entryFor(PayrollScenarioFixture.Scenario scenario, String code) {
        AdjustmentPatchDto entry = new AdjustmentPatchDto();
        entry.setId(scenario.adjustment(code).getId());
        return entry;
    }

    @Test
    @DisplayName("18. a hard total override is refused where the scheme does not allow one")
    void aTotalOverrideIsRefusedWhereNotAllowed() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        // MEAL_ALLOWANCE allows the unit price to be edited, not the total.
        AdjustmentPatchDto entry = entryFor(scenario, "MEAL_ALLOWANCE");
        entry.setAmount(new BigDecimal("9999.00"));
        entry.setOverrideReason("because");

        assertThatThrownBy(() -> payrollRunItemService.patch(scenario.item().getId(), patchOf(entry)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("MEAL_ALLOWANCE");
    }

    @Test
    @DisplayName("18b. a hard total override without a reason is refused")
    void aTotalOverrideNeedsAReason() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        // TRANSPORT_ALLOWANCE does allow the total to be set — but D7 says a figure
        // the calculation did not produce has to say why.
        AdjustmentPatchDto entry = entryFor(scenario, "TRANSPORT_ALLOWANCE");
        entry.setAmount(new BigDecimal("1234.00"));

        assertThatThrownBy(() -> payrollRunItemService.patch(scenario.item().getId(), patchOf(entry)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Razlog");
    }

    @Test
    @DisplayName("18c. with the reason, the override is applied and recorded")
    void aTotalOverrideWithAReasonIsApplied() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        AdjustmentPatchDto entry = entryFor(scenario, "TRANSPORT_ALLOWANCE");
        entry.setAmount(new BigDecimal("1234.00"));
        entry.setOverrideReason("Dodatna nadoknada po odluci direktora");
        payrollRunItemService.patch(scenario.item().getId(), patchOf(entry));

        var adjustment = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "TRANSPORT_ALLOWANCE")
                .orElseThrow();

        assertThat(adjustment.getAmount()).isEqualByComparingTo("1234.00");
        assertThat(adjustment.getIsOverridden()).isTrue();
        assertThat(adjustment.getOverrideReason()).isEqualTo("Dodatna nadoknada po odluci direktora");
        // The system figure survives, so the payslip can show both.
        assertThat(adjustment.getSystemAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("18d. editing a permitted input is not an override and needs no reason")
    void editingAPermittedInputIsNotAnOverride() {
        var scenario = fixture.scenario().build();
        calculate(scenario);

        AdjustmentPatchDto entry = entryFor(scenario, "MEAL_ALLOWANCE");
        entry.setUnitAmount(new BigDecimal("350.00"));
        payrollRunItemService.patch(scenario.item().getId(), patchOf(entry));

        var adjustment = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "MEAL_ALLOWANCE")
                .orElseThrow();

        assertThat(adjustment.getUnitAmount()).isEqualByComparingTo("350.00");
        assertThat(adjustment.getIsOverridden())
                .as("the formula still runs, so this is not a bypass")
                .isFalse();
    }

    @Test
    @DisplayName("18e. a line the scheme forces to zero cannot be typed into, by any client")
    void aForcedZeroLineRefusesEveryEdit() {
        fixture.ensureScheme("IT-COMM-3", "Komercijala", true, false);
        var scenario = fixture.scenario().scheme("IT-COMM-3").build();
        fixture.forceZero("IT-COMM-3", "MONTHLY_BONUS");
        calculate(scenario);

        // The commercial bonus is shown at 0,00 and there is no route to a number
        // in it. Enforcing that in the UI alone would leave it open to anybody with
        // the API — which is what "allow_override is decoration" meant.
        AdjustmentPatchDto entry = entryFor(scenario, "MONTHLY_BONUS");
        entry.setCorrectionAmount(new BigDecimal("5000.00"));

        assertThatThrownBy(() -> payrollRunItemService.patch(scenario.item().getId(), patchOf(entry)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("uvek je nula");
    }

    @Test
    @DisplayName("18f. clearing an override returns the line to the system figure")
    void clearingAnOverrideRestoresTheSystemFigure() {
        var scenario = fixture.scenario().build();
        fixture.dailyReport(scenario.employee(), LocalDate.of(2026, 9, 3), 6, 480, 450);
        calculate(scenario);

        AdjustmentPatchDto set = entryFor(scenario, "TRANSPORT_ALLOWANCE");
        set.setAmount(new BigDecimal("1234.00"));
        set.setOverrideReason("po odluci");
        payrollRunItemService.patch(scenario.item().getId(), patchOf(set));

        AdjustmentPatchDto clear = entryFor(scenario, "TRANSPORT_ALLOWANCE");
        clear.setClearOverride(true);
        payrollRunItemService.patch(scenario.item().getId(), patchOf(clear));

        var adjustment = adjustmentRepository
                .findByItemIdAndCategoryCode(scenario.item().getId(), "TRANSPORT_ALLOWANCE")
                .orElseThrow();

        assertThat(adjustment.getAmount()).isEqualByComparingTo("350.00");
        assertThat(adjustment.getIsOverridden()).isFalse();
        assertThat(adjustment.getOverrideReason()).isNull();
    }

    @Test
    @DisplayName("18g. the response carries the scheme's answer, so the client needs no rules")
    void theResponseCarriesTheEffectiveConfiguration() {
        fixture.ensureScheme("IT-COMM-4", "Komercijala", true, false);
        var scenario = fixture.scenario().scheme("IT-COMM-4").build();
        fixture.forceZero("IT-COMM-4", "MONTHLY_BONUS");
        calculate(scenario);

        var bonus = payrollRunItemService.getDetails(scenario.monthlyReport().getId())
                .getAdjustments().stream()
                .flatMap(section -> section.getAdjustments().stream())
                .filter(line -> "MONTHLY_BONUS".equals(line.getCategoryCode()))
                .findFirst().orElseThrow();

        // Everything the UI needs in order to render a commercial bonus correctly,
        // without knowing what "commercial" is.
        assertThat(bonus.getVisibleInUi()).isTrue();
        assertThat(bonus.getShowWhenZero()).isTrue();
        assertThat(bonus.getCalculationMode()).isEqualTo("ZERO");
        assertThat(bonus.getEditableInput()).isEqualTo("NONE");
        assertThat(bonus.getAllowTotalOverride()).isFalse();
        assertThat(bonus.getAmount()).isEqualByComparingTo("0.00");
    }

    // 13 and 13b are live now, in the classes where the behaviour lives:
    //   EmployeeCompensationSchemeChangeIT — a change lands on the first of a month
    //   PayrollSchemeScopeIT               — two schemes in one month is an error
    //   PayrollSchemeScopeBatchingIT       — no scheme stops the whole run

    // ═══════════════════════════════════════════════════════════════════════
    // Manual lines are editable — regression, reported 2026-08-02
    // ═══════════════════════════════════════════════════════════════════════
    // "Ukupan iznos stavke PAID_PART_2 se ne može uneti ručno." A MANUAL category
    // has no calculator, so its system_amount stays 0; the override check compared
    // the typed figure against that 0, called it a bypassed formula and demanded
    // allow_total_override. Eight of thirteen categories were uneditable — every
    // one whose editable_input is AMOUNT.
    @Nested
    @DisplayName("11. a line whose input IS the amount")
    class AmountEditableLines {

        @Test
        @DisplayName("11a. accepts a typed amount without demanding a total override")
        void acceptsATypedAmount() {
            var scenario = fixture.scenario().build();
            AdjustmentPatchDto edit = new AdjustmentPatchDto();
            edit.setId(scenario.adjustment("PAID_PART_2").getId());
            edit.setAmount(new BigDecimal("5000.00"));

            PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
            patch.setAdjustments(List.of(edit));
            payrollRunItemService.patch(scenario.item().getId(), patch);

            var line = adjustmentRepository
                    .findById(scenario.adjustment("PAID_PART_2").getId()).orElseThrow();
            assertThat(line.getAmount()).isEqualByComparingTo("5000.00");
            assertThat(line.getHasManualInput()).isTrue();
            // Not an override: there was no calculated figure to override.
            assertThat(line.getIsOverridden()).isFalse();
            assertThat(line.getOverrideReason()).isNull();
        }

        @Test
        @DisplayName("11b. still refuses a typed total where the amount is NOT the input")
        void stillRefusesATypedTotalElsewhere() {
            var scenario = fixture.scenario().build();
            AdjustmentPatchDto edit = new AdjustmentPatchDto();
            edit.setId(scenario.adjustment("MEAL_ALLOWANCE").getId());
            edit.setAmount(new BigDecimal("99999.00"));

            PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
            patch.setAdjustments(List.of(edit));

            // MEAL_ALLOWANCE is priced per meal: the unit amount is the input and
            // the system supplies the count. The fix must not have opened this up.
            assertThatThrownBy(() -> payrollRunItemService.patch(scenario.item().getId(), patch))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("MEAL_ALLOWANCE");
        }
    }
}
