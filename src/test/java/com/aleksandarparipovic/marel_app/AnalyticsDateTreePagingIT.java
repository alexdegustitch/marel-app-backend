package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsOptionDto;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsPageDto;
import com.aleksandarparipovic.marel_app.analytics.dto.EmployeeProductOperationDto;
import com.aleksandarparipovic.marel_app.analytics.dto.NormBasisDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductDateOperationEmployeeDto;
import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page 2 pages by DAY, and a day is never split.
 *
 * <p>WHY THIS EXISTS. The report draws a date → shift → product → operation tree whose every
 * band states that band's totals. Those totals are only true if the band is whole: page the
 * report by ROWS and the last day on a chunk arrives half-finished, so its subtotal — and its
 * shift's, and its product's — silently states a part of itself as the whole. Paging by day
 * is what makes the numbers on those bands mean what they say, and this is the test that says
 * so out loud.
 *
 * <p>The second half covers the other mode: a chosen sort is a RANKING, which a tree cannot
 * hold, so the report flattens and pages by row across everything the filters left standing.
 *
 * <p>The fixture writes fact rows directly. The queries under test read {@code
 * work_log_facts}, and going through the recalc worker would test the sync path instead.
 */
@Transactional
class AnalyticsDateTreePagingIT extends AbstractIntegrationTest {

    @Autowired private AnalyticsQueryRepository analyticsQueryRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private JdbcTemplate jdbc;

    private static final LocalDate DAY_1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 6, 2);
    private static final LocalDate DAY_3 = LocalDate.of(2026, 6, 3);

    @Test
    @DisplayName("a page of days carries every row of the days it names, oldest first")
    void aPageOfDaysIsWholeDays() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        // One DAY per chunk — the hardest case for the promise being made: if paging ever
        // counted rows, a size of 1 would cut every day after its first row.
        AnalyticsFilterRequest filter = treeFilter(data.productId, 1, 0);
        AnalyticsPageDto<ProductDateOperationEmployeeDto> first = analyticsQueryRepository.findDateTreePage(filter);

        // Three days were recorded, so the report is three pages long — regardless of how
        // many rows each day holds.
        assertThat(first.page().totalElements()).isEqualTo(3);
        assertThat(first.page().totalPages()).isEqualTo(3);

        // 2 shifts x 2 operations, all of it on the page, none of it on the next one.
        assertThat(first.content()).hasSize(4);
        assertThat(first.content()).allMatch(row -> DAY_1.equals(row.getWorkDate()));

        // Shifts come in the order they START — I, II, III — not in the order their codes
        // happen to be spelled.
        assertThat(first.content().stream().map(ProductDateOperationEmployeeDto::getShiftTypeId).distinct().toList())
                .containsExactly(data.morningShiftId, data.afternoonShiftId);

        assertThat(analyticsQueryRepository.findDateTreePage(treeFilter(data.productId, 1, 1)).content())
                .hasSize(4)
                .allMatch(row -> DAY_2.equals(row.getWorkDate()));
        assertThat(analyticsQueryRepository.findDateTreePage(treeFilter(data.productId, 1, 2)).content())
                .hasSize(4)
                .allMatch(row -> DAY_3.equals(row.getWorkDate()));
    }

    @Test
    @DisplayName("the whole period's totals survive being read one day at a time")
    void chunksSumToTheWholePeriod() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        long scrolled = 0;
        for (int page = 0; page < 3; page++) {
            scrolled += analyticsQueryRepository.findDateTreePage(treeFilter(data.productId, 1, page))
                    .content().stream()
                    .mapToLong(ProductDateOperationEmployeeDto::getSumQuantity)
                    .sum();
        }

        long inOneGo = analyticsQueryRepository.findDateTreePage(treeFilter(data.productId, 60, 0))
                .content().stream()
                .mapToLong(ProductDateOperationEmployeeDto::getSumQuantity)
                .sum();

        assertThat(scrolled).isEqualTo(inOneGo);
        assertThat(scrolled).isEqualTo(data.totalQuantity);
    }

    @Test
    @DisplayName("ordering the days keeps them days; newest first is still a tree")
    void aDateSortStillPagesByDay() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        AnalyticsFilterRequest filter = treeFilter(data.productId, 1, 0);
        filter.setSortBy("workDate");
        filter.setSortDir("DESC");

        AnalyticsPageDto<ProductDateOperationEmployeeDto> first = analyticsQueryRepository.findDateTreePage(filter);

        assertThat(first.page().totalElements()).isEqualTo(3);
        assertThat(first.content()).hasSize(4);
        assertThat(first.content()).allMatch(row -> DAY_3.equals(row.getWorkDate()));
    }

    @Test
    @DisplayName("a sorted report ranks across the whole period and pages by row")
    void aSortFlattensAndPagesByRow() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        AnalyticsFilterRequest filter = treeFilter(data.productId, 2, 0);
        filter.setSortBy("sumQuantity");
        filter.setSortDir("DESC");

        AnalyticsPageDto<ProductDateOperationEmployeeDto> first = analyticsQueryRepository.findDateTreePage(filter);

        // Now a page is a page of ROWS: 3 days x 2 shifts x 2 operations = 12 of them.
        assertThat(first.page().totalElements()).isEqualTo(12);
        assertThat(first.content()).hasSize(2);

        // The ranking is over the WHOLE period, not restarted inside each day — which is the
        // only reading of "sortiraj po količini" that answers the question asked.
        List<Long> quantities = first.content().stream()
                .map(ProductDateOperationEmployeeDto::getSumQuantity)
                .toList();
        assertThat(quantities).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(quantities.getFirst()).isEqualTo(data.largestRowQuantity);
    }

    @Test
    @DisplayName("the operation filter offers only the chosen products' operations")
    void operationOptionsFollowTheChosenProducts() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        List<Long> offered = analyticsQueryRepository
                .findDistinctOperations(null, 200, List.of(data.productId)).stream()
                .map(AnalyticsOptionDto::id)
                .toList();

        assertThat(offered).containsExactlyInAnyOrder(data.operationAId, data.operationBId);
        assertThat(offered).doesNotContain(data.otherProductOperationId);

        // Without the narrowing the other product's operation is offered again — the list is
        // narrowed by the products, not by anything permanent about the operations.
        assertThat(analyticsQueryRepository.findDistinctOperations(null, 500).stream()
                .map(AnalyticsOptionDto::id).toList())
                .contains(data.otherProductOperationId);
    }

    @Test
    @DisplayName("the candidate norm is pieces per hour, one line per operation")
    void normBasisIsThroughputPerOperation() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        List<NormBasisDto> norms = analyticsQueryRepository.findNormBasis(treeFilter(data.productId, 60, 0));

        // A norm belongs to an operation, so a product's answer is one line per operation.
        assertThat(norms).hasSize(2);

        NormBasisDto first = norms.stream()
                .filter(n -> n.getOperationId().equals(data.operationAId)).findFirst().orElseThrow();

        // Six logs of one hour each: 100+105+110+115+120+125 = 675 pieces over 6 hours.
        assertThat(first.getSumQuantity()).isEqualTo(675);
        assertThat(first.getSumDurationMin()).isEqualTo(360);
        assertThat(first.getAvgPerHour()).isEqualByComparingTo(new java.math.BigDecimal("112.5"));

        // The norm in force travels with it, so the two numbers are read side by side.
        assertThat(first.getCurrentNorm()).isEqualTo(40);
    }

    @Test
    @DisplayName("a bound reshapes the candidate, because it decides what counts as representative")
    void normBasisFollowsTheBounds() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        AnalyticsFilterRequest bounded = treeFilter(data.productId, 60, 0);
        // Applied to a ROW of the report — one worker, one operation, one shift, one day — so
        // three of operation A's six hours drop out before anything is totalled across them.
        bounded.setMinQuantity(115L);

        NormBasisDto first = analyticsQueryRepository.findNormBasis(bounded).stream()
                .filter(n -> n.getOperationId().equals(data.operationAId)).findFirst().orElseThrow();

        // 115 + 120 + 125 = 360 pieces over 3 hours, not 675 over 6.
        assertThat(first.getSumQuantity()).isEqualTo(360);
        assertThat(first.getSumDurationMin()).isEqualTo(180);
        assertThat(first.getAvgPerHour()).isEqualByComparingTo(new java.math.BigDecimal("120"));
    }

    @Test
    @DisplayName("the worker report pages by WORKER, at operation-of-a-product grain")
    void employeeReportPagesByWorker() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        AnalyticsFilterRequest filter = new AnalyticsFilterRequest();
        filter.setProductIds(List.of(data.productId));
        filter.setGroupByEmployee(true);
        filter.setSize(1);
        filter.setPage(0);

        AnalyticsPageDto<EmployeeProductOperationDto> first =
                analyticsQueryRepository.findEmployeeEfficiencyPage(filter);

        // One worker did all of it, so the report is one page long — however many rows the
        // worker's own page holds.
        assertThat(first.page().totalElements()).isEqualTo(1);

        // And that worker arrives WHOLE: both operations of the product, not the first one.
        assertThat(first.content()).hasSize(2);
        assertThat(first.content()).extracting(EmployeeProductOperationDto::getOperationId)
                .containsExactlyInAnyOrder(data.operationAId, data.operationBId);

        // Totalled across every day and shift the filters left standing.
        assertThat(first.content().stream()
                .filter(r -> r.getOperationId().equals(data.operationAId))
                .findFirst().orElseThrow().getSumQuantity()).isEqualTo(675);
    }

    @Test
    @DisplayName("a sorted worker report flattens and pages by row")
    void employeeReportFlattensWhenSorted() {
        Fixture data = seedThreeDaysTwoShiftsTwoOperations();

        AnalyticsFilterRequest filter = new AnalyticsFilterRequest();
        filter.setProductIds(List.of(data.productId));
        filter.setGroupByEmployee(true);
        filter.setSortBy("sumQuantity");
        filter.setSortDir("DESC");
        filter.setSize(1);
        filter.setPage(0);

        AnalyticsPageDto<EmployeeProductOperationDto> first =
                analyticsQueryRepository.findEmployeeEfficiencyPage(filter);

        // Now a page is a page of ROWS: two of them, and one per page.
        assertThat(first.page().totalElements()).isEqualTo(2);
        assertThat(first.content()).hasSize(1);
        // Operation B produced 681 to A's 675, so it ranks first.
        assertThat(first.content().getFirst().getOperationId()).isEqualTo(data.operationBId);
    }

    /** What one seeded period produced, so each test can assert against it by name. */
    private record Fixture(
            long productId,
            long operationAId,
            long operationBId,
            long otherProductOperationId,
            long morningShiftId,
            long afternoonShiftId,
            long totalQuantity,
            long largestRowQuantity) {}

    /**
     * Three days, two shifts a day, two operations of one product on each shift.
     *
     * <p>Twelve fact rows over three days: enough that a chunk of ONE day is unambiguously
     * more than one row, which is the whole point of the promise under test. Quantities rise
     * with the day so a ranking has something to rank.
     */
    private Fixture seedThreeDaysTwoShiftsTwoOperations() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();

        Product product = fixture.product("IT-DateTree-Product");
        Operation operationA = fixture.operation(product, scenario.workCategory(), 40);
        Operation operationB = fixture.operation(product, scenario.workCategory(), 40);

        // A second product's operation, worked in the same period, to prove the option list is
        // narrowed by the chosen products and not merely ordered differently. It has to have
        // been worked at all — the list only ever offers operations that appear in the facts.
        Product otherProduct = fixture.product("IT-DateTree-Other");
        Operation otherProductOperation = fixture.operation(otherProduct, scenario.workCategory(), 40);
        WorkShift firstShift = null;

        long total = 0;
        long largest = 0;
        Long morningShiftId = null;
        Long afternoonShiftId = null;

        LocalDate[] days = {DAY_1, DAY_2, DAY_3};
        for (int dayIndex = 0; dayIndex < days.length; dayIndex++) {
            // Two shift types, keyed by start hour — 06:00 is the earlier one, so it is the
            // one the report must band first.
            for (int startHour : new int[]{6, 14}) {
                WorkShift shift = fixture.workShift(scenario.employee(), days[dayIndex], startHour, 480);
                if (firstShift == null) firstShift = shift;
                long shiftTypeId = shift.getShift().getId();
                if (startHour == 6) morningShiftId = shiftTypeId;
                else afternoonShiftId = shiftTypeId;

                List<Operation> operations = List.of(operationA, operationB);
                for (int opIndex = 0; opIndex < operations.size(); opIndex++) {
                    Operation operation = operations.get(opIndex);
                    int quantity = 100 + dayIndex * 10 + (startHour == 6 ? 0 : 5) + opIndex;
                    // The two logs of a shift are kept apart in time — one stretch of work
                    // cannot run while another is still running.
                    WorkLog workLog = fixture.workLog(
                            shift, operation, scenario.workCategory(), opIndex * 120, 60, quantity);
                    insertFact(workLog, shift, operation, product, quantity, 60);
                    total += quantity;
                    largest = Math.max(largest, quantity);
                }
            }
        }

        // One stretch of the other product's work, on the first shift, after the two above it.
        WorkLog otherLog = fixture.workLog(
                firstShift, otherProductOperation, scenario.workCategory(), 240, 60, 7);
        insertFact(otherLog, firstShift, otherProductOperation, otherProduct, 7, 60);

        return new Fixture(product.getId(), operationA.getId(), operationB.getId(),
                otherProductOperation.getId(), morningShiftId, afternoonShiftId, total, largest);
    }

    /** The report as it opens: the tree, one chunk of days at a time. */
    private AnalyticsFilterRequest treeFilter(long productId, int size, int page) {
        AnalyticsFilterRequest filter = new AnalyticsFilterRequest();
        // Narrowed to this test's own product: the fact table is shared, and a total that
        // counts somebody else's seed data is a total that says nothing.
        filter.setProductIds(List.of(productId));
        filter.setGroupByDate(true);
        filter.setSize(size);
        filter.setPage(page);
        return filter;
    }

    /**
     * One fact row for one work log, written straight to SQL.
     *
     * <p>The fact table's key is the work log's own id, so the log has to exist — the sync
     * service is skipped, not the row it would have written.
     */
    private void insertFact(WorkLog workLog, WorkShift shift, Operation operation, Product product,
                            int quantity, int durationMin) {
        jdbc.update("""
                INSERT INTO work_log_facts (
                    work_log_id, work_shift_id, employee_id, operation_id, product_id,
                    shift_type_id, work_date, month_start, shift_code, operation_start_time,
                    product_name, operation_name, duration_min, quantity, scrap,
                    approved_performance_rate)
                SELECT
                    ?, ws.id, ws.employee_id, ?, ?, ws.shift_id, ws.work_date,
                    date_trunc('month', ws.work_date)::date, s.shift_code, s.start_time,
                    ?, ?, ?, ?, 0, 100
                FROM work_shifts ws JOIN shifts s ON s.id = ws.shift_id
                WHERE ws.id = ?
                """,
                workLog.getId(), operation.getId(), product.getId(),
                product.getProductName(), operation.getOpName(), durationMin, quantity,
                shift.getId());
    }
}
