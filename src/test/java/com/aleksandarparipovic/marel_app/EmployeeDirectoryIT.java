package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.EmployeeService;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDirectorySummary;
import com.aleksandarparipovic.marel_app.employee.view.EmployeeWithBonusView;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The employee directory over the search endpoint it is built on.
 *
 * <p>Three properties the redesigned screen depends on and nothing else
 * exercised:
 *
 * <ul>
 *   <li>the summary above the table agrees with the table — its total is the
 *       sum of its per-scheme counts, and both equal what the page reports;</li>
 *   <li>paging is deterministic even when nobody asked for a sort: walking the
 *       pages one row at a time visits every employee exactly once. Before the
 *       id tiebreaker there was no ORDER BY at all under the DISTINCT, and the
 *       same person could appear on two pages while another appeared on
 *       none;</li>
 *   <li>the global search finds a name by a fragment typed from the middle of
 *       it — the case the trigram indexes exist for.</li>
 * </ul>
 */
@Transactional
class EmployeeDirectoryIT extends AbstractIntegrationTest {

    @Autowired private EmployeeService employeeService;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("the summary's total is the sum of its scheme counts, and matches the page")
    void summaryAgreesWithThePage() {
        Employee standard = fixture.scenario().scheme(CompensationSchemeCodes.STANDARD).build().employee();
        Employee foreign = fixture.scenario().scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).build().employee();

        SearchRequest everything = request(0, 200, null);
        Page<EmployeeWithBonusView> page = employeeService.searchAll(everything, EmployeeWithBonusView.class);
        EmployeeDirectorySummary summary = employeeService.directorySummary(everything);

        assertThat(summary.total())
                .as("the summary counts the same employees the page does")
                .isEqualTo(page.getTotalElements());
        assertThat(summary.bySchemeCode().stream().mapToLong(EmployeeDirectorySummary.SchemeCount::count).sum())
                .as("the total is exactly the sum of the per-scheme counts")
                .isEqualTo(summary.total());

        assertThat(countFor(summary, CompensationSchemeCodes.STANDARD))
                .as("the standard employee is counted under STANDARD")
                .isGreaterThanOrEqualTo(1);
        assertThat(countFor(summary, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT))
                .as("the foreign employee is counted under FOREIGN_FIXED_COEFFICIENT")
                .isGreaterThanOrEqualTo(1);

        assertThat(page.getContent()).extracting(EmployeeWithBonusView::getEmployeeId)
                .contains(standard.getId(), foreign.getId());
    }

    @Test
    @DisplayName("a scheme filter narrows the page but never the summary")
    void schemeFilterIsDroppedFromTheSummary() {
        fixture.scenario().scheme(CompensationSchemeCodes.STANDARD).build();
        fixture.scenario().scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).build();

        SearchRequest onlyForeign = request(0, 200, null);
        SearchRequest.FilterField scheme = new SearchRequest.FilterField();
        scheme.setField("schemeCode");
        scheme.setOperator(SearchRequest.Operator.EQ);
        scheme.setValue(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT);
        onlyForeign.setFilters(List.of(scheme));

        Page<EmployeeWithBonusView> page = employeeService.searchAll(onlyForeign, EmployeeWithBonusView.class);
        EmployeeDirectorySummary summary = employeeService.directorySummary(onlyForeign);

        assertThat(page.getContent())
                .as("the page honours the scheme filter")
                .allMatch(row -> CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT.equals(row.getSchemeCode()));
        assertThat(countFor(summary, CompensationSchemeCodes.STANDARD))
                .as("the summary still counts the other schemes — that is what the tiles are for")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("unsorted pages visit every employee exactly once")
    void unsortedPagingIsDeterministic() {
        fixture.scenario().build();
        fixture.scenario().build();
        fixture.scenario().build();

        long total = employeeService.searchAll(request(0, 200, null), EmployeeWithBonusView.class).getTotalElements();

        Set<Long> seen = new HashSet<>();
        long visited = 0;
        for (int pageIndex = 0; pageIndex < total; pageIndex++) {
            Page<EmployeeWithBonusView> page =
                    employeeService.searchAll(request(pageIndex, 1, null), EmployeeWithBonusView.class);
            assertThat(page.getContent()).hasSize(1);
            visited++;
            assertThat(seen.add(page.getContent().get(0).getEmployeeId()))
                    .as("page %d repeats an employee already shown on an earlier page", pageIndex)
                    .isTrue();
        }
        assertThat(visited).isEqualTo(total);
        assertThat(seen).hasSize((int) total);
    }

    @Test
    @DisplayName("a fragment from the middle of the name finds the employee")
    void globalSearchMatchesAFragment() {
        Employee employee = fixture.scenario().build().employee();
        // "Golden Employee 7" → "den emp": across the space, mid-word on both sides.
        String fragment = employee.getFullName().substring(3, 10).toUpperCase();

        Page<EmployeeWithBonusView> page =
                employeeService.searchAll(request(0, 50, fragment), EmployeeWithBonusView.class);

        assertThat(page.getContent()).extracting(EmployeeWithBonusView::getEmployeeId)
                .contains(employee.getId());
        assertThat(employeeService.directorySummary(request(0, 50, fragment)).total())
                .as("the summary searches the same way the page does")
                .isEqualTo(page.getTotalElements());
    }

    private static long countFor(EmployeeDirectorySummary summary, String schemeCode) {
        return summary.bySchemeCode().stream()
                .filter(c -> schemeCode.equals(c.schemeCode()))
                .mapToLong(EmployeeDirectorySummary.SchemeCount::count)
                .sum();
    }

    private static SearchRequest request(int page, int size, String globalSearch) {
        SearchRequest request = new SearchRequest();
        SearchRequest.Pagination pagination = new SearchRequest.Pagination();
        pagination.setPage(page);
        pagination.setSize(size);
        request.setPagination(pagination);
        request.setSort(List.of());
        request.setFilters(List.of());
        request.setGlobalSearch(globalSearch);
        return request;
    }
}
