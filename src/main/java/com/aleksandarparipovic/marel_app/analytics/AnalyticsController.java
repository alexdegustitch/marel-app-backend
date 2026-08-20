package com.aleksandarparipovic.marel_app.analytics;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsOptionDto;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsPageDto;
import com.aleksandarparipovic.marel_app.analytics.dto.EmployeeProductOperationDto;
import com.aleksandarparipovic.marel_app.analytics.dto.NormBasisDto;
import com.aleksandarparipovic.marel_app.analytics.dto.NoteOccurrenceDto;
import com.aleksandarparipovic.marel_app.analytics.dto.OperationEmployeeDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductDateOperationEmployeeDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductOperationSummaryDto;
import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsQueryRepository queryRepo;

    /**
     * Page 1 — Proizvod-operacija, one page at a time.
     *
     * <p>Paged and sorted on the server, unlike the other four: this is the one report whose
     * row count follows the number of OPERATIONS (10–15k of them), not the number of products
     * or employees, so it is the one that would eventually be asked to draw a table nobody's
     * browser can hold.
     */
    @PostMapping("/product-operation")
    public AnalyticsPageDto<ProductOperationSummaryDto> productOperation(@RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findProductOperationSummaryPage(filter);
    }

    /**
     * The work logs behind a note search — what "Detaljnije" on a page 1 row opens.
     *
     * <p>Takes the report's own filter, narrowed by the caller to the clicked row's product
     * (and operation, when the report is at operation grain). Capped server-side: a two-letter
     * note fragment matches more logs than any panel can show.
     */
    @PostMapping("/note-occurrences")
    public List<NoteOccurrenceDto> noteOccurrences(
            @RequestBody AnalyticsFilterRequest filter,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return queryRepo.findNoteOccurrences(filter, Math.max(1, Math.min(limit, 1000)));
    }

    /**
     * Page 4 — Efikasnost proizvoda, one page at a time.
     *
     * <p>The same aggregate page 1 reads, at the same grain and through the same paging: a
     * page is a page of PRODUCTS, each arriving with every operation it has, so a product's
     * band always carries its whole total. The two pages differ in what they ASK of it — page
     * 1 offers a grain toggle and the note trail, page 4 draws it as a foldable tree of four
     * measures — which is a question for the screen, not for the query.
     */
    @PostMapping("/product-efficiency")
    public AnalyticsPageDto<ProductOperationSummaryDto> productEfficiency(
            @RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findProductOperationSummaryPage(filter);
    }

    /**
     * Page 2 — Datum-smena-proizvod-operacija-radnik, one page at a time.
     *
     * <p>Paged and sorted on the server for the same reason as page 1: over a period of any
     * length the report is more rows than a browser can hold, and a ranking of the chunk that
     * happened to arrive is not a ranking. In its default view a page is a page of DATES —
     * a day arrives whole, so the day's and the shift's subtotals are never a part of
     * themselves; see {@code groupByDate} on the filter.
     */
    @PostMapping("/product-date-operation-employee")
    public AnalyticsPageDto<ProductDateOperationEmployeeDto> productDateOperationEmployee(
            @RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findDateTreePage(filter);
    }

    /**
     * Page 3 — Efikasnost radnika, one page at a time.
     *
     * <p>Aggregated to (worker, product, operation) grain: the report answers "how is this
     * worker doing, and on what". In its default view a page is a page of WORKERS — each
     * arrives with everything they worked, so a worker's total is never a part of itself.
     */
    @PostMapping("/employee-efficiency")
    public AnalyticsPageDto<EmployeeProductOperationDto> employeeEfficiency(
            @RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findEmployeeEfficiencyPage(filter);
    }

    /**
     * Page 5 — Efikasnost operacija, one page at a time.
     *
     * <p>Aggregated to (operation, worker) grain: the report answers "how is this operation
     * going, and for whom". In its default view a page is a page of OPERATIONS — each arrives
     * with every worker who ran it, so an operation's total is never a part of itself.
     */
    @PostMapping("/operation-efficiency")
    public AnalyticsPageDto<OperationEmployeeDto> operationEfficiency(
            @RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findOperationEfficiencyPage(filter);
    }

    /**
     * Each operation's norm beside what the filtered work says it could be — what the norm
     * panel on page 2 shows once a single product is chosen.
     *
     * <p>Takes the report's own filter, bounds included, because the candidate norm is only
     * meaningful as "the throughput of the work I am currently looking at". Narrowing the
     * period or raising a bound re-answers it.
     */
    @PostMapping("/norm-basis")
    public List<NormBasisDto> normBasis(@RequestBody AnalyticsFilterRequest filter) {
        return queryRepo.findNormBasis(filter);
    }

    // Backs the "napomena" multi-select filter option list on all 5 analytics pages.
    @GetMapping("/filters/notes")
    public List<String> distinctNotes() {
        return queryRepo.findDistinctNotes();
    }

    // Backs the product/operation/employee multi-select filter option lists.
    @GetMapping("/filters/products")
    public List<AnalyticsOptionDto> distinctProducts() {
        return queryRepo.findDistinctProducts();
    }

    /**
     * Operations that were worked, searched on the server.
     *
     * <p>There are 10–15k of them, so the list is not something a select can hold:
     * the client sends what the user typed and gets a page back. `limit` is capped
     * here rather than trusted — an open-ended page size is a way to ask for the
     * whole table by accident.
     */
    @GetMapping("/filters/operations")
    public List<AnalyticsOptionDto> distinctOperations(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "50") int limit,
            // Present when the report already filters by product: an operation of some other
            // product would AND with that filter to an empty report, so it is not offered.
            @RequestParam(required = false) List<Long> productIds
    ) {
        return queryRepo.findDistinctOperations(search, Math.max(1, Math.min(limit, 200)), productIds);
    }

    @GetMapping("/filters/employees")
    public List<AnalyticsOptionDto> distinctEmployees() {
        return queryRepo.findDistinctEmployees();
    }
}
