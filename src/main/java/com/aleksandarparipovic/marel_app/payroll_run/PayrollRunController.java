package com.aleksandarparipovic.marel_app.payroll_run;

import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunCreateRequest;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunInfoDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunResponse;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSearchHit;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollRunSummaryDto;
import com.aleksandarparipovic.marel_app.payroll_run.dto.PayrollYearOverview;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll-runs")
@RequiredArgsConstructor
public class PayrollRunController {

    private final PayrollRunService payrollRunService;

    /** GET /api/payroll-runs/years — years that actually hold obračuni, newest first. */
    @GetMapping("/years")
    public ResponseEntity<List<Integer>> getYearsWithRuns() {
        return ResponseEntity.ok(payrollRunService.getYearsWithRuns());
    }

    /** GET /api/payroll-runs/year/{year} — summaries per employee for entire year */
    @GetMapping("/year/{year}")
    public ResponseEntity<List<PayrollRunSummaryDto>> getSummariesByYear(@PathVariable int year) {
        return ResponseEntity.ok(payrollRunService.getSummariesByYear(year));
    }

    /**
     * GET /api/payroll-runs/year-overview?year= — the whole year in one answer:
     * twelve months, their counts by status, their sums, and the obračuni the
     * caller last had open. Sums and the locked count are withheld from anyone
     * without payroll access, in the response.
     */
    @GetMapping("/year-overview")
    public ResponseEntity<PayrollYearOverview> getYearOverview(@RequestParam int year) {
        return ResponseEntity.ok(payrollRunService.getYearOverview(year));
    }

    /**
     * GET /api/payroll-runs/search?year=&q=&size= — the obračuni of one year
     * whose worker's name or number contains {@code q}.
     */
    @GetMapping("/search")
    public ResponseEntity<List<PayrollRunSearchHit>> search(
            @RequestParam int year,
            @RequestParam String q,
            @RequestParam(defaultValue = "8") int size) {
        return ResponseEntity.ok(payrollRunService.searchInYear(year, q, size));
    }

    /** GET /api/payroll-runs/last-activity?year=&month= — last 3 items touched by current user */
    @GetMapping("/last-activity")
    public ResponseEntity<List<PayrollRunSummaryDto>> getLastActivity(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(payrollRunService.getLastActivity(year, month));
    }

    /** GET /api/payroll-runs?year=&month=&page=&size=&globalSearch=&sort=&status= — paged per-employee list */
    @GetMapping(params = {"year", "month"})
    public ResponseEntity<Page<PayrollRunInfoDto>> getPagedByYearAndMonth(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) String globalSearch,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return ResponseEntity.ok(payrollRunService.getPagedByYearAndMonth(year, month, globalSearch, status, pageable));
    }

    @GetMapping
    public ResponseEntity<List<PayrollRunResponse>> findAll() {
        return ResponseEntity.ok(payrollRunService.findAll().stream().map(PayrollRunResponse::new).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayrollRunResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<PayrollRunResponse> create(@Valid @RequestBody PayrollRunCreateRequest request) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PayrollRunResponse> update(@PathVariable Long id, @RequestBody PayrollRun entity) {
        return ResponseEntity.ok(new PayrollRunResponse(payrollRunService.update(id, entity)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payrollRunService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
